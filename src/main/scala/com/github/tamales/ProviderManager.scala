package com.github.tamales

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.github.tamales.Provider.Refresh
import com.github.tamales.ProviderManager.Schedule
import com.github.tamales.impls.{Confluence, Evernote, Jira}

import scala.collection._

object ProviderManager {
  def props(events:TasksEventBus) = Props(new ProviderManager(events))

  final case object Schedule
}

/** Maintains a set of configured providers and dispatch the `Refresh` command
  * to all of them.
  *
  * @param events  The event bus used to publish a {@link TaskFound} event
  */
class ProviderManager(val events:TasksEventBus) extends Actor with ActorLogging with ActorConfig {
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  private var providers = mutable.TreeSet.empty[ActorRef]
  override def preStart() = {
    if ( isConfigured("providers.evernote") ) {
      providers += context.actorOf(Evernote.props(events), "evernote")
    }
    if ( isConfigured("providers.jira") ) {
      providers += context.actorOf(Jira.props(events), "jira")
    }
    log.info("Provider manager started with {} provider(s)", providers.size)
  }

  override def receive = {
    case Refresh =>
      log.info("Refreshing {} provider(s)", providers.size)
      providers.foreach { _ ! Refresh }
    case Schedule =>
      log.info("Scheduling refresh for now")
      context.system.scheduler.schedule(0.seconds, 30.minutes, self, Refresh)
  }

}
