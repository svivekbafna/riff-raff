package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import tasks._
import java.io.File
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.JString


class PackageTypeTest extends FlatSpec with ShouldMatchers {

  "jetty web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))

    val jetty = new JettyWebappPackageType(p)
    val host = Host("host_name")

    jetty.actions("deploy")(host) should be (List(
      BlockFirewall(host as "jetty"),
      CopyFile(host as "jetty", "/tmp/packages/webapp", "/jetty-apps/"),
      Restart(host as "jetty", "webapp"),
      WaitForPort(host, "8080", 20 seconds),
      UnblockFirewall(host as "jetty")
    ))
  }

  it should "allow port to be overriden" in {
    val basic = Package("webapp", Set.empty, Map.empty, "jetty-webapp", new File("/tmp/packages/webapp"))
    basic.data("port") should be (JString("8080"))

    val overridden = Package("webapp", Set.empty, Map("port" -> "80"), "jetty-webapp", new File("/tmp/packages/webapp"))
    overridden.data("port") should be (JString("80"))
  }



  "django web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "django-webapp", new File("/tmp/packages/webapp-build.7"))
    val django = new DjangoWebappPackageType(p)
    val host = Host("host_name")

    django.actions("deploy")(host) should be (List(
      BlockFirewall(host as "django"),
      SetSwitch(host, "80", "HEALTHCHECK_OK", false),
      CopyFile(host as "django", "/tmp/packages/webapp-build.7", "/django-apps/webapp-build.7"),
      LinkFile(host as "django", "/django-apps/webapp-build.7", "/django-apps/webapp"),
      Restart(host as "django", "webapp"),
      WaitForPort(host, "80", 20 seconds),
      SetSwitch(host, "80", "HEALTHCHECK_OK", true),
      UnblockFirewall(host as "django")
    ))
  }

//  it should "have an upgrade_database action" in {
//    val p = Package("webapp", Set.empty, Map.empty, "django-database", new File("/tmp/packages/webapp"))
//    val django = new DjangoWebappPackageType(p)
//    val host = Host("host_name")
//
//    django.actions("upgrade_database")(host) should be (List(
//      CopyFile(host as "django", "/tmp/packages/webapp", "/tmp/webapp-dbdeploy"),
//      DjangoManagmentCmd(host as "django", "/tmp/webapp-dbdeploy", "syncdb --noinput"),
//      DjangoManagmentCmd(host as "django", "/tmp/webapp-dbdeploy", "migrate --noinput")
//    ))
//  }
}