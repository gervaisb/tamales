package com.github.tamales

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, PoisonPill, Props, Terminated}
import com.github.tamales.Provider.Refresh
import com.github.tamales.impls.{Evernote, Exchange, GitLab, Jira}

object ProviderManager {
  def props(events:TasksEventBus) = Props(new ProviderManager(events))
}

/** Maintains a set of configured providers and dispatch the `Refresh` command
  * to all of them.
  *
  * @param events The event bus used to publish a [[com.github.tamales.Publisher.TaskFound]] event
  */
class ProviderManager(val events:TasksEventBus) extends Actor with ActorLogging with ActorConfig {

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy(){
    case cause =>
      val provider = cause.getStackTrace.find(_.getClassName.startsWith("com.github.tamales.impls"))
        .map(name => name.getClassName.substring("com.github.tamales.impls.".length))
        .map(name => s"$name provider")
        .getOrElse("One provider")
      log.warning("{} failed due to \"{}\"; stopping it.", provider, cause)
      Stop
  }

  override def preStart(): Unit = {
    if ( isConfigured("providers.evernote") ) {
      context.actorOf(Evernote.props(events), "evernote")
    }
    if ( isConfigured("providers.jira") ) {
      context.actorOf(Jira.props(events), "jira")
    }
    if ( isConfigured("providers.exchange") ) {
      context.actorOf(Exchange.props(events), "exchange")
    }
    if ( isConfigured("providers.gitlab") ) {
      context.actorOf(GitLab.props(events), "gitlab")
    }
    providers.foreach(context.watch)
    log.info("Provider manager started with {} provider(s)", providers.size)
  }

  override def receive: Receive = {
    case Refresh =>
      log.debug("Refreshing {} provider(s)", providers.size)
      providers.foreach { _ ! Refresh }
    case Terminated(actor) =>
      if ( providers.isEmpty ) {
        log.info("All providers are terminated, terminating the manager")
        self ! PoisonPill
      } else {
        log.debug("Provider {} terminated. ({} remaining)", actor.path.name, providers.size)
      }
  }

  private def providers = context.children

}
