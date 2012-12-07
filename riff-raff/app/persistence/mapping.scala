package persistence

import java.util.UUID
import org.joda.time.DateTime
import akka.agent.Agent
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.novus.salat._
import com.novus.salat.StringTypeHintStrategy
import controllers.Logging
import deployment.{DeployV2Record, Record, DeployRecord}
import magenta._
import akka.actor.ActorSystem
import controllers.SimpleDeployDetail

trait DocumentGraters {
  RegisterJodaTimeConversionHelpers()
  def loader:Option[ClassLoader]
  val documentContext = {
    val context = new Context {
      val name = "global"
      override val typeHintStrategy = StringTypeHintStrategy(TypeHintFrequency.WhenNecessary)
    }
    loader.foreach(context.registerClassLoader(_))
    context.registerPerClassKeyOverride(classOf[DeployRecordDocument], remapThis = "uuid", toThisInstead = "_id")
    context
  }
  val deployGrater = {
    implicit val context = documentContext
    grater[DeployRecordDocument]
  }
  val logDocumentGrater = {
    implicit val context = documentContext
    grater[LogDocument]
  }
}

trait RecordConverter {
  def uuid:UUID
  def startTime:DateTime
  def params: ParametersDocument
  def status:RunState.Value
  lazy val deployDocument = DeployRecordDocument(uuid, startTime, params, status)
  def logDocuments:Seq[LogDocument]
}

case class RecordV1Converter(uuid:UUID, startTime:DateTime, params: ParametersDocument, status:RunState.Value, messageStacks:List[MessageStack] = Nil) extends RecordConverter with Logging {
  def +(newStack: MessageStack): RecordConverter = copy(messageStacks = messageStacks ::: List(newStack))
  def +(newStatus: RunState.Value): RecordConverter = copy(status = newStatus)

  def apply(stack: MessageStack): Option[LogDocument] = None

  def apply: (DeployRecordDocument, Seq[LogDocument]) = (deployDocument, logDocuments)

  lazy val logDocuments = Nil
}

case class RecordV2Converter(uuid:UUID, startTime:DateTime, params: ParametersDocument, status:RunState.Value, messages:List[MessageWrapper] = Nil) extends RecordConverter with Logging {
  def +(newWrapper: MessageWrapper): RecordV2Converter = copy(messages = messages ::: List(newWrapper))
  def +(newStatus: RunState.Value): RecordV2Converter = copy(status = newStatus)

  def apply(message: MessageWrapper): Option[LogDocument] = {
    val stackId=message.messageId
    logDocuments.find(_.id == stackId)
  }

  def apply: (DeployRecordDocument, Seq[LogDocument]) = (deployDocument, logDocuments)

  lazy val logDocuments = {
    val logDocumentSeq: Seq[LogDocument] = messages.map(LogDocument(_))
    val ids = logDocumentSeq.map(_.id)
    if (ids.size != ids.toSet.size) log.error("Key collision detected in log of deploy %s" format uuid)
    logDocumentSeq
  }
}

object RecordConverter {
  def apply(record: DeployRecord): RecordConverter = {
    val sourceParams = record.parameters
    val params = ParametersDocument(
      deployer = sourceParams.deployer.name,
      projectName = sourceParams.build.projectName,
      buildId = sourceParams.build.id,
      stage = sourceParams.stage.name,
      recipe = sourceParams.recipe.name,
      hostList = sourceParams.hostList,
      deployType = record.taskType.toString
    )
    RecordV1Converter(record.uuid, record.time, params, record.state, record.messageStacks)
  }

  def apply(record: DeployV2Record): RecordV2Converter = {
    val sourceParams = record.parameters
    val params = ParametersDocument(
      deployer = sourceParams.deployer.name,
      projectName = sourceParams.build.projectName,
      buildId = sourceParams.build.id,
      stage = sourceParams.stage.name,
      recipe = sourceParams.recipe.name,
      hostList = sourceParams.hostList,
      deployType = record.taskType.toString
    )
    RecordV2Converter(record.uuid, record.time, params, record.state, record.messages)
  }
}

case class DocumentConverter(deploy: DeployRecordDocument, logs: Seq[LogDocument]) {

  lazy val parameters = DeployParameters(
    Deployer(deploy.parameters.deployer),
    Build(deploy.parameters.projectName, deploy.parameters.buildId),
    Stage(deploy.parameters.stage),
    RecipeName(deploy.parameters.recipe),
    deploy.parameters.hostList
  )

  lazy val deployRecord =
    DeployV2Record(
      deploy.startTime,
      deploy.deployTypeEnum,
      deploy.uuid,
      parameters,
      messageWrappers,
      Some(deploy.status)
    )

  lazy val messageWrappers: List[MessageWrapper] = {
    if (logs.isEmpty) Nil else convertToMessageWrappers(LogDocumentTree(logs))
  }

  def convertToMessageWrappers(tree: LogDocumentTree): List[MessageWrapper] = convertToMessageWrappers(tree, tree.roots.head)

  def convertToMessageWrappers(tree: LogDocumentTree, log: LogDocument, messagesTail: List[Message] = Nil): List[MessageWrapper] = {
    val children = tree.childrenOf(log).toList
    log.document match {
      case leaf if children.isEmpty =>
        List(messageWrapper(log, MessageStack(leaf.asMessage(parameters, messagesTail.headOption) :: messagesTail, log.time)))
      case node => {
        val message:Message = node.asMessage(parameters)
        messageWrapper(log,MessageStack(StartContext(message) :: messagesTail, log.time)) ::
          children.flatMap(child => convertToMessageWrappers(tree, child, message :: messagesTail))
      }
    }
  }

  def messageWrapper(log: LogDocument, stack: MessageStack): MessageWrapper = {
    MessageWrapper(MessageContext(log.deploy, parameters, log.parent), log.id, stack)
  }
}

trait DocumentStore {
  def writeDeploy(deploy: DeployRecordDocument) {}
  def writeLog(log: LogDocument) {}
  def updateStatus(uuid: UUID, status: RunState.Value) {}
  def readDeploy(uuid: UUID): Option[DeployRecordDocument] = None
  def readLogs(uuid: UUID): Iterable[LogDocument] = Nil
  def getDeployV2UUIDs(limit: Int = 0): Iterable[SimpleDeployDetail] = Nil
  def getDeploysV2(limit: Int = 0): Iterable[DeployRecordDocument] = Nil
  def deleteDeployLogV2(uuid: UUID) {}
}

object DocumentStoreConverter extends Logging {
  val documentStore: DocumentStore = Persistence.store

  def saveDeploy(record: DeployV2Record) {
    if (!record.messages.isEmpty) throw new IllegalArgumentException
    val converter = RecordConverter(record)
    documentStore.writeDeploy(converter.deployDocument)
    converter.logDocuments.foreach(documentStore.writeLog)
  }

  def saveMessage(message: MessageWrapper) {
    documentStore.writeLog(LogDocument(message))
  }

  def updateDeployStatus(record: DeployV2Record) {
    documentStore.updateStatus(record.uuid, record.state)
  }

  def getDeployDocument(uuid:UUID) = documentStore.readDeploy(uuid)
  def getDeployLogs(uuid:UUID) = documentStore.readLogs(uuid)

  def getDeploy(uuid:UUID, fetchLog: Boolean = true): Option[DeployV2Record] = {
    try {
      val deployDocument = getDeployDocument(uuid)
      val logDocuments = if (fetchLog) getDeployLogs(uuid) else Nil
      deployDocument.map { deploy =>
        DocumentConverter(deploy, logDocuments.toSeq).deployRecord
      }
    } catch {
      case e:Exception =>
        log.error("Couldn't get DeployV2Record for %s" format uuid, e)
        None
    }
  }

  def getDeployList(limit: Int, fetchLog: Boolean = true): Seq[DeployV2Record] = {
    documentStore.getDeploysV2(limit).toSeq.flatMap{ deployDocument =>
      try {
        val logs = if (fetchLog) getDeployLogs(deployDocument.uuid) else Nil
        Some(DocumentConverter(deployDocument, logs.toSeq).deployRecord)
      } catch {
        case e:Exception =>
          log.error("Couldn't get DeployV2Record for %s" format deployDocument.uuid, e)
          None
      }
    }
  }
}