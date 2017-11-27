package com.github.tamales

import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.github.tamales.Provider.Refresh
import com.github.tamales.impls.Trello


object TamalesSupervisor {
  def props() = Props(new TamalesSupervisor)
}

class TamalesSupervisor extends Actor with ActorLogging {
  override def preStart(): Unit = log.info("Tamales application started")
  override def postStop(): Unit = log.info("Tamales application stopped")

  private val publisher = context.actorOf(Trello.props(), "publisher-trello")

  private val events = new TasksEventBus
  events.subscribe(publisher)

  private val providers = context.actorOf(ProviderManager.props(events), "providers")
  context.watch(providers)


  override def receive: Receive = {
    case Terminated(actor) if (actor == providers) =>
      log.info("All providers are terminated, terminating Tamales")
      context.system.terminate()
  }

  providers ! Refresh

}
