package magenta.artifact

import java.io.File

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import com.gu.management.Loggable
import magenta.{Build, DeployReporter}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

trait S3Location {
  def bucket: String
  def key: String
  def prefixElements: List[String] = key.split("/").toList
  def fileName:String = prefixElements.last
  def extension:Option[String] = if (fileName.contains(".")) Some(fileName.split('.').last) else None
  def relativeTo(path: S3Location): String = {
    key.stripPrefix(path.key).stripPrefix("/")
  }
  def fetchContentAsString()(implicit client: AmazonS3) = S3Location.fetchContentAsString(this)
  def listAll()(implicit client: AmazonS3) = S3Location.listObjects(this)
}

object S3Location extends Loggable {
  def listAll(bucket: String)(implicit s3Client: AmazonS3): Seq[S3Object] = listObjects(bucket, None)

  def listObjects(location: S3Location)(implicit s3Client: AmazonS3): Seq[S3Object] =
    listObjects(location.bucket, Some(location.key))

  val maxKeysInBucketListing = 1000 // AWS won't return more than this, even if you set the parameter to a larger value

  private def listObjects(bucket: String, prefix: Option[String])(implicit s3Client: AmazonS3): Seq[S3Object] = {
    def request(continuationToken: Option[String]) = new ListObjectsV2Request()
      .withBucketName(bucket)
      .withPrefix(prefix.orNull)
      .withMaxKeys(maxKeysInBucketListing)
      .withContinuationToken(continuationToken.orNull)

    @tailrec
    def pageListings(acc: Seq[ListObjectsV2Result], previousListing: ListObjectsV2Result): Seq[ListObjectsV2Result] = {
      if (!previousListing.isTruncated) {
        acc
      } else {
        val listing = s3Client.listObjectsV2(
          request(Some(previousListing.getNextContinuationToken))
        )
        pageListings(acc :+ listing, listing)
      }
    }

    val initialListing = s3Client.listObjectsV2(request(None))
    for {
      summaries <- pageListings(Seq(initialListing), initialListing)
      summary <- summaries.getObjectSummaries.asScala
    } yield S3Object(summary.getBucketName, summary.getKey, summary.getSize)
  }

  def fetchContentAsString(location: S3Location)(implicit client:AmazonS3):Option[String] = {
    Try {
      Some(client.getObjectAsString(location.bucket, location.key))
    }.recover {
      case e: AmazonS3Exception if e.getStatusCode == 404 => None
    }.get
  }
}

case class S3Path(bucket: String, key: String) extends S3Location

object S3Path {
  def apply(location: S3Location, key: String): S3Path = {
    val delimiter = if (location.key.endsWith("/")) "" else "/"
    S3Path(location.bucket, s"${location.key}$delimiter$key")
  }
}

case class S3Object(bucket: String, key: String, size: Long) extends S3Location

trait S3Artifact extends S3Location {
  def deployObjectName: String
  def deployObject = S3Path(this, deployObjectName)
}

object S3Artifact {
  def buildPrefix(build: Build): String = {
    s"${build.projectName}/${build.id}/"
  }
}

case class S3JsonArtifact(bucket: String, key: String) extends S3Artifact {
  val deployObjectName: String = "deploy.json"
}

object S3JsonArtifact extends Loggable {
  def apply(build: Build, bucket: String): S3JsonArtifact = {
    val prefix = S3Artifact.buildPrefix(build)
    S3JsonArtifact(bucket, prefix)
  }

  def withZipFallback[T](artifact: S3JsonArtifact)(f: S3JsonArtifact => Try[T])(implicit client: AmazonS3, reporter: DeployReporter): T = {
    val attempt = f(artifact) recoverWith {
      case NonFatal(e) =>
        convertFromZipBundle(artifact)
        f(artifact)
    }
    attempt.get
  }

  def convertFromZipBundle(artifact: S3JsonArtifact)(implicit client: AmazonS3, reporter: DeployReporter): Unit = {
    reporter.warning("DEPRECATED: The artifact.zip is now a legacy format - please switch to the new format (if you are using sbt-riffraff-artifact then simply upgrade to >= 0.9.4, if you use the TeamCity upload plugin you'll need to use the riffRaffNotifyTeamcity task instead of the riffRaffArtifact task)")
    reporter.info("Converting artifact.zip to S3 layout")
    implicit val sourceBucket: Option[String] = Some(artifact.bucket)
    S3ZipArtifact.withDownload(artifact){ dir =>
      val filesToUpload = resolveFiles(dir, artifact.key)
      reporter.info(s"Uploading contents of artifact (${filesToUpload.size} files) to S3")
      filesToUpload.foreach{ case (file, key) =>
        val metadata = new ObjectMetadata()
        metadata.setContentLength(file.length)
        val req = new PutObjectRequest(artifact.bucket, key, file).withMetadata(metadata)
        client.putObject(req)
      }
      reporter.info(s"Zip artifact converted")
    }(client, reporter)

    S3ZipArtifact.delete(artifact)
    reporter.verbose("Zip artifact deleted")
  }

  private def subDirectoryPrefix(key: String, file:File): String = {
    if (key.isEmpty) {
      file.getName
    } else {
      val delimiter = if (key.endsWith("/")) "" else "/"
      s"$key$delimiter${file.getName}"
    }
  }
  private def resolveFiles(file: File, key: String): Seq[(File, String)] = {
    if (!file.isDirectory) Seq((file, key))
    else file.listFiles.toSeq.flatMap(f => resolveFiles(f, subDirectoryPrefix(key, f))).distinct
  }
}

case class S3YamlArtifact(bucket: String, key: String) extends S3Artifact {
  val deployObjectName: String = "riff-raff.yaml"
}

object S3YamlArtifact {
  def apply(build: Build, bucket: String): S3YamlArtifact = {
    val prefix = S3Artifact.buildPrefix(build)
    S3YamlArtifact(bucket, prefix)
  }
}
