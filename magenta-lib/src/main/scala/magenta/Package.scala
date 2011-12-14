package magenta

import java.io.File
import net.liftweb.json.JsonAST.{JString, JValue}
import java.util.NoSuchElementException

case class Package(
  name: String,
  pkgApps: Set[App],
  pkgSpecificData: Map[String, JValue],
  pkgTypeName: String,
  srcDir: File) {

  def mkAction(name: String): Action = pkgType.mkAction(name)

  lazy val pkgType = pkgTypeName match {
    case "jetty-webapp" => new JettyWebappPackageType(this)
    case "resin-webapp" => new ResinWebappPackageType(this)
    case "django-webapp" => new DjangoWebappPackageType(this)
    case "file" => new FilePackageType(this)
    case "demo" => new DemoPackageType(this)
    case unknown => sys.error("Package type %s of package %s is unknown" format (unknown, name))
  }

  val data = pkgType.defaultData ++ pkgSpecificData
  def stringData(key: String): String = data(key) match { case JString(s) => s case _ => throw new NoSuchElementException() }

  val apps = pkgApps
}