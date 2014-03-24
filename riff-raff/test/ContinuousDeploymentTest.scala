package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import ci.{Trigger, ContinuousDeploymentConfig, ContinuousDeployment}
import java.util.UUID
import magenta.{Build => MagentaBuild}
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import magenta.RecipeName
import ci.teamcity.{Project, BuildType, BuildSummary}
import java.net.URL
import org.joda.time.DateTime

class ContinuousDeploymentTest extends FlatSpec with ShouldMatchers {

  "Continuous Deployment" should "create deploy parameters for a set of builds" in {
    val params = continuousDeployment.deployParamsForSuccessfulBuilds(newBuildsList, contDeployConfigs).toSet
    params.size should be(2)
    params should be(Set(
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "392"), Stage("QA"), RecipeName("default")),
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))
    ))
  }

  it should "work out the latest build for different branches" in {
    val params = continuousDeployment.deployParamsForSuccessfulBuilds(newBuildsList, contDeployBranchConfigs).toSet
    val expected = Set(
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "392"), Stage("QA"), RecipeName("default")),
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy2", "391"), Stage("PROD"), RecipeName("default")),
      DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))
    )
    params.size should be(expected.size)
    params should be(expected)
  }

  it should "not act on new tag events with successful build configs" in {
    val params = continuousDeployment.deployParamsForTaggedBuilds(newBuildsList, "tag", contDeployConfigs).toSet
    params.size should be(0)
  }

  it should "act on new tag events with BuildTagged" in {
    val params = continuousDeployment.deployParamsForTaggedBuilds(newBuildsList, "tag", contDeployTagConfigs).toSet
    params.size should be(1)
    params should be(Set(DeployParameters(Deployer("Continuous Deployment"), MagentaBuild("tools::deploy", "71"), Stage("PROD"), RecipeName("default"))))
  }

  it should "not act on a new tag event when the tag doesn't match the BuildTagged config" in {
    val params = continuousDeployment.deployParamsForTaggedBuilds(newBuildsList, "otherTag", contDeployTagConfigs).toSet
    params.size should be(0)
  }

  /* Test types */

  val tdProdEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "PROD", "default", None, Trigger.SuccessfulBuild, None, "Test user")
  val tdCodeDisabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "CODE", "default", None, Trigger.Disabled, None, "Test user")
  val td2ProdDisabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "PROD", "default", None, Trigger.Disabled, None, "Test user")
  val td2QaEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "QA", "default", None, Trigger.SuccessfulBuild, None, "Test user")
  val td2QaBranchEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "QA", "default", Some("branch"), Trigger.SuccessfulBuild, None, "Test user")
  val td2ProdBranchEnabled = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy2", "PROD", "default", Some("master"), Trigger.SuccessfulBuild, None, "Test user")
  val tdProdEnabledTag = ContinuousDeploymentConfig(UUID.randomUUID(), "tools::deploy", "PROD", "default", None, Trigger.BuildTagged, Some("tag"), "Test user")
  val contDeployConfigs = Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaEnabled)
  val contDeployBranchConfigs = Seq(tdProdEnabled, tdCodeDisabled, td2ProdDisabled, td2QaBranchEnabled, td2ProdBranchEnabled)
  val contDeployTagConfigs = Seq(tdProdEnabledTag)

  val continuousDeployment = new ContinuousDeployment()

  val url = new URL("http://riffraff.gnl/test")

  val tdBT = BuildType("bt204", "deploy", Project("project1", "tools"), url)
  val tdB70 = BuildSummary(45394, "70", tdBT.id, "SUCCESS", url, "master", None, new DateTime(2013,1,24,14,42,47), tdBT)
  val tdB71 = BuildSummary(45397, "71", tdBT.id, "SUCCESS", url, "master", None, new DateTime(2013,1,25,14,42,47), tdBT)

  val td2BT = BuildType("bt205", "deploy2", Project("project1", "tools"), new URL("http://riffraff.gnl/test"))
  val td2B390 = BuildSummary(45395, "390", td2BT.id, "SUCCESS", url, "branch", None, new DateTime(2013,1,24,14,43,47), td2BT)
  val td2B391 = BuildSummary(45396, "391", td2BT.id, "SUCCESS", url, "master", None, new DateTime(2013,1,25,14,44,47), td2BT)
  val td2B392 = BuildSummary(45400, "392", td2BT.id, "SUCCESS", url, "branch", None, new DateTime(2013,1,25,15,34,47), td2BT)

  val newBuildsList = List( tdB70, tdB71, td2B390, td2B391, td2B392 )
}
