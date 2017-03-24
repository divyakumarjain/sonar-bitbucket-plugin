package ch.mibex.bitbucket.sonar.review

import java.net.URLEncoder

import ch.mibex.bitbucket.sonar.{SonarBBPlugin, SonarBBPluginConfig}
import ch.mibex.bitbucket.sonar.client._
import ch.mibex.bitbucket.sonar.utils.{LogUtils, SonarUtils}
import org.sonar.api.CoreProperties
import org.sonar.api.batch.CheckProject
import org.sonar.api.batch.postjob.{PostJob, PostJobContext, PostJobDescriptor}
import org.sonar.api.resources.Project
import org.sonar.api.utils.log.Loggers

import scala.collection.JavaConverters._


// due to https://jira.sonarsource.com/browse/SONAR-6398, a post job is not called on SonarQube 5.1.0!
class SonarReviewPostJob(bitbucketClient: BitbucketClient,
                         pluginConfig: SonarBBPluginConfig,
                         reviewCommentsUpdater: ReviewCommentsCreator) extends PostJob with CheckProject {
  private val logger = Loggers.get(getClass)

  override def execute(context: PostJobContext): Unit = {
    getPullRequestsToAnalyze foreach { pullRequest =>
      logger.info(LogUtils.f(s"Plug-in is active and will analyze pull request with #${pullRequest.id}..."))
      handlePullRequest(context, pullRequest)
    }
  }

  private def getProjectUrl(context: PostJobContext, pullRequest: PullRequest) =
    context.settings().getString(CoreProperties.SERVER_BASE_URL) + "/dashboard?id=" +
      context.settings().getString(CoreProperties.PROJECT_KEY_PROPERTY) + ":" +
      URLEncoder.encode(pullRequest.srcBranch, "UTF-8")

  private def handlePullRequest(context: PostJobContext, pullRequest: PullRequest) {
    setBuildStatus(InProgressBuildStatus, context, pullRequest)
    val ourComments = bitbucketClient.findOwnPullRequestComments(pullRequest)
    val report = new PullRequestReviewResults(pluginConfig)
    val allIssues = context.issues().asScala
    val commentsToDelete = reviewCommentsUpdater.createOrUpdateComments(pullRequest, allIssues, ourComments, report)
    deletePreviousComments(pullRequest, commentsToDelete)
    deletePreviousGlobalComments(pullRequest, ourComments)
    createGlobalComment(pullRequest, report)
    approveOrUnapproveIfEnabled(pullRequest, report)
    setBuildStatus(report.calculateBuildStatus(), context, pullRequest)
  }

  private def setBuildStatus(buildStatus: BuildStatus, context: PostJobContext, pullRequest: PullRequest) {
    if (pluginConfig.buildStatusEnabled()) {
      bitbucketClient.updateBuildStatus(pullRequest, buildStatus, getProjectUrl(context, pullRequest))
    }
  }

  private def getPullRequestsToAnalyze =
    if (pluginConfig.pullRequestId() != 0) findPullRequestWithConfiguredId(pluginConfig.pullRequestId()).toList
    else findPullRequestsForConfiguredBranch

  private def findPullRequestWithConfiguredId(pullRequestId: Int): Option[PullRequest] = {
    val pullRequest = bitbucketClient.findPullRequestWithId(pullRequestId)

    if (pullRequest.isEmpty) {
      logger.info(LogUtils.f(
        s"""Pull request with id '$pullRequestId' not found.
            |No analysis will be performed.""".stripMargin.replaceAll("\n", " ")))
    }

    pullRequest
  }

  private def findPullRequestsForConfiguredBranch = {
    val branchName = pluginConfig.branchName()
    val pullRequests = bitbucketClient.findPullRequestsWithSourceBranch(branchName)
    if (pullRequests.isEmpty) {
      logger.info(LogUtils.f(
        s"""No open pull requests with source branch '$branchName' found.
            |No analysis will be performed.""".stripMargin.replaceAll("\n", " ")))
    }
    pullRequests
  }

  private def approveOrUnapproveIfEnabled(pullRequest: PullRequest, report: PullRequestReviewResults) {
    if (pluginConfig.approveUnApproveEnabled()) {
      if (report.canBeApproved) {
        bitbucketClient.approve(pullRequest)
      } else {
        bitbucketClient.unApprove(pullRequest)
      }
    }
  }

  private def createGlobalComment(pullRequest: PullRequest, report: PullRequestReviewResults) {
    bitbucketClient.createPullRequestComment(pullRequest = pullRequest, message = report.formatAsMarkdown())
  }

  private def deletePreviousGlobalComments(pullRequest: PullRequest, ownComments: Seq[PullRequestComment]) {
    ownComments
      .filterNot(_.isInline)
      .filter(_.content.startsWith(SonarUtils.sonarMarkdownPrefix()))
      .foreach { c =>
        bitbucketClient.deletePullRequestComment(pullRequest, c.commentId)
      }
  }

  private def deletePreviousComments(pullRequest: PullRequest, commentsToDelete: Map[Int, PullRequestComment]) {
    commentsToDelete foreach { case (commentId, comment) =>
      if (comment.content.startsWith(SonarUtils.sonarMarkdownPrefix())) {
        bitbucketClient.deletePullRequestComment(pullRequest, commentId)
      }
    }
  }

  override def shouldExecuteOnProject(project: Project): Boolean = {
    if (logger.isDebugEnabled) {
      logger.debug(LogUtils.f("\nPlug-in config: {}\n"), pluginConfig)
    }
    pluginConfig.validate()
    true
  }

  override def describe(descriptor: PostJobDescriptor): Unit = {
    descriptor
      .name("Sonar Plug-in for Bitbucket Cloud")
      .requireProperties(SonarBBPlugin.BitbucketAccountName, SonarBBPlugin.BitbucketRepoSlug)
  }
}