package com.perevillega

import java.util.{Calendar, Date}

import com.danielasfregola.twitter4s.TwitterRestClient
import com.danielasfregola.twitter4s.entities.{RatedData, Tweet}
import com.danielasfregola.twitter4s.util.Configurations
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps


object DeleteOldTweets extends App with StrictLogging {

  def eraseOldTweets(f: Future[Seq[Tweet]], deletef: (Long, Boolean) ⇒ Future[Tweet]): Unit = {
    val allTweetsFuture = f.map { allTweets ⇒
      logger.info(s"Obtained ${allTweets.size} tweets")
      val filteredTweets = selectOlderThan(allTweets, cutoffDate)
      logger.info(s"Found ${filteredTweets.size} tweets older than $cutoffDate")
      logger.info(s"Twitter doesn't like you deleting more than a few tweets at once it seems, so we trim the list down to this if needed. Just run it more than once.")
      val downsized = filteredTweets.take(100)
      deleteTweets(downsized, deletef)
      logger.info("Tweets deleted")
    }

    Await.result(allTweetsFuture, 10 minutes)
  }

  def selectOlderThan(tweets: Seq[Tweet], cutoffDate: Date): Seq[Tweet] = {
    tweets.filter(_.created_at.before(cutoffDate))
  }

  def deleteTweets(tweets: Seq[Tweet], f: (Long, Boolean) ⇒ Future[Tweet]): Unit = {
    tweets.zipWithIndex.foreach { case (t, i) ⇒
      logger.info(s"Deleting tweet ${i + 1} of ${tweets.size}")
      Await.result(f(t.id, false), 3 seconds)
    }
  }


  def timelineTweets(restClient: TwitterRestClient, userName: String, since: Option[Long]): Future[RatedData[Seq[Tweet]]] = restClient.userTimelineForUser(userName, count = 200, max_id = since)

  def favouritedTweets(restClient: TwitterRestClient, userName: String, since: Option[Long]): Future[RatedData[Seq[Tweet]]] = restClient.favoriteStatusesForUser(userName, count = 200, max_id = since)

  def getTweets(restClient: TwitterRestClient, userName: String, f: (TwitterRestClient, String, Option[Long]) ⇒ Future[RatedData[Seq[Tweet]]]): Future[Seq[Tweet]] = {

    def getAllRecursively(tweets: Seq[Tweet], since: Option[Long]): Future[Seq[Tweet]] = {
      f(restClient, userName, since)
        .flatMap { timeline ⇒
          val data = timeline.data
          if (data.isEmpty) {
            logger.info("No more results, returning accumulated results")
            Future.successful(tweets)
          } else {
            val lastId = data.last.id
            if (since.contains(lastId)) {
              logger.info("We reached end of available tweets, returning accumulated results")
              Future.successful(tweets)
            } else {
              logger.info(s"Received ${data.size} tweets, requesting next batch")
              getAllRecursively(tweets ++ data, Some(lastId))
            }
          }
        }
    }

    getAllRecursively(Seq.empty, None)
  }


  logger.info(s"Select cut off date")
  val rightNow = Calendar.getInstance
  rightNow.add(Calendar.MONTH, -2)
  val cutoffDate = new Date(rightNow.toInstant.toEpochMilli)
  logger.info(s"Cut off date is [$cutoffDate]")

  logger.info(s"Initialise Rest API and other tools")
  val restClient = TwitterRestClient()
  val userName = Configurations.config.getString("user.handle")


  logger.info(s"Process timeline tweets for user [$userName]")
  eraseOldTweets(getTweets(restClient, userName, timelineTweets), restClient.deleteTweet)

  logger.info(s"Process favourite tweets for user [$userName]")
  eraseOldTweets(getTweets(restClient, userName, favouritedTweets), restClient.unfavoriteStatus)

}
