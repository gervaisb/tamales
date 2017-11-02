package com.github.tamales

/**
  * Define the contract (commands and events) of a provider
  */
object Provider {

  /** Command that trigger the provider to find tasks.
    * The implementation can decide to return only new or updated tasks since
    * last refresh or all tasks. The provider must be able to deal with existing
    * tasks.
    */
  final case object Refresh

}
