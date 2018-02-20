[![BCH compliance](https://bettercodehub.com/edge/badge/gervaisb/tamales?branch=master)](https://bettercodehub.com/)

# Tamales
> _Tamales_ is a standalone service intended to synchronize tasks between 
different systems. 

Being a big fan of [Taco](https://www.tacoapp.com) I started to cry when having 
to integrate tasks from on-premise systems like [Jira](https://jira.atlassian.com).
So I decided to create another wheel.

_Tamales_ are mexican street food. This name was chosen to keep a friendly 
reference to _Taco_. 
 
# Usage
**There is no binary download at this time**, you should build your own. 

An usual configuration is to start it when your session open and let it work on 
the background. _tamales_ use a default refresh period of 30 minutes. 

## Configuration
_Tamales_ expect a `$USER_HOME/.tamales/configuration.properties` file where it reads 
the configuration of each source or provider. If the configuration is missing for a 
provider, this one is not used.

    providers {
        jira {
          location = "https://jira.acme.com"
          password = "clear text password !"
          username = "username"
        }
        
        evernote {
          token = "everenote api token"
        }
        
        exchange {
          account = "username@domain"
          password = "clear text password !"
          url = "https://exchange.acme.com/EWS/Exchange.asmx"
        }
    }
    
    publishers {
        trello {
            api {
                key = "trello api key"
                token = "trello api token"
            }
            boardId = "id of the board that must be synchronized"
            list {
                incompleteId = "id of the list for new tasks"
                completeId = "if of the list for completed tasks"
            }
        }
    }

_______________________________________________________________________________

# Contribution

## Build
After cloning the project, a simple `sbt universal:packageBin` should produce 
a zip with the required binaries into _target\universal_.

    $ git clone ..
    $ cd tamales
    $ sbt universal:packageBin

## Architecture
Build on [Akka](https://akka.lightbend.io), _tamales_ is made of two major kind 
of actors, the _providers_ and _publishers_. 
+ A _provider_ is managed by the `ProviderManager`. It accept the `Refresh` 
command and publish `TaskFound` events. Optionally, a _provider_ may react to 
the `TaskSaved` event to update the task.
+ A _ publisher_ listen to the `TaskFound` event, create or update the `Task` 
and send a `TaskSaved` command to the event issuer. 

The supervisor build and register the provider to the event bus and start the 
provider manager.

The provider manager maintains a set of configured provider and dispatch the 
`Refresh` event to all of them.   


            [Provider]   [ProviderManager]    [TasksEventBus]    [Publisher]
                |               |                    |                |
                |               |                    |                |
               [ ]<--Refresh---[ ]                   |                |
               [ ]----------TaskFound-------------->[ ]               |
                |               |                   [ ]--TaskFound-->[ ]
               [ ]<-----------------TaskSaved------------------------[ ]                          
                |               |                    |                |
                
