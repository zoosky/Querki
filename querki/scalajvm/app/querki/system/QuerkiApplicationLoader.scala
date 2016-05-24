package querki.system

import akka.actor._
import akka.util.Timeout
import scala.concurrent.duration._

import com.google.inject.AbstractModule

import com.typesafe.conductr.bundlelib.akka.{ Env => AkkaEnv }
import com.typesafe.conductr.bundlelib.play.{ Env => PlayEnv }
import com.typesafe.config.ConfigFactory
import play.api.inject.guice.GuiceApplicationLoader
import play.api.{ Configuration, Application, ApplicationLoader }

// For cleaning up afterwards:
import scala.concurrent.Future
import javax.inject._
import play.api.inject.ApplicationLifecycle

import querki.ecology._
import querki.globals._

import QuerkiRoot._

/**
 * The top of Querki Initialization, as of Play 2.4. This actually loads the app and gets things
 * started.
 */
class QuerkiApplicationLoader extends ApplicationLoader {
  
  import QuerkiApplicationLoader._

  var ecology:Ecology = null
  
  val initTermDuration = 30 seconds
  implicit val initTermTimeout = Timeout(initTermDuration)

  def load(context: ApplicationLoader.Context): Application = {
    // Configure ConductR. This is taken directly from the ConductR docs:
    //   http://conductr.lightbend.com/docs/1.1.x/AkkaAndPlay
    val conductRConfig = Configuration(AkkaEnv.asConfig) ++ Configuration(PlayEnv.asConfig)
    val newConfig = context.initialConfiguration ++ conductRConfig
    val newContext = context.copy(initialConfiguration = newConfig)
        
    // Boot the core of the application from the Play POV:
    QLog.spew(s"About to start GuiceApplicationLoader")
    val app = (new GuiceApplicationLoader).load(newContext)
    QLog.spew(s"GuiceApplicationLoader started")
    
    // Prep ConductR, if it's present:
    val config = AkkaEnv.asConfig
    // I suspect this fallback shouldn't be "application", but if I set to it anything else I
    // get errors. It really feels like there are internals that are looking for "application".
    val systemName = sys.env.getOrElse("BUNDLE_SYSTEM", "")
    val systemVersion = sys.env.getOrElse("BUNDLE_SYSTEM_VERSION", "1")
    val fullSystemName =
      if (systemName.length > 0)
        s"$systemName-$systemVersion"
      else
        "application"
    QLog.spew(s"Starting the main ActorSystem as $fullSystemName")
    _appSystem = 
      ActorSystem(
        name = fullSystemName, 
        config = Some(config.withFallback(ConfigFactory.load())), 
        classLoader = Some(app.classloader))
    QLog.spew(s"ActorSystem started")
    
    // TEMP: some startup debugging, to see what I can do:
    QLog.spew(s"Querki starting...")
    def env(name:String) = sys.env.getOrElse(name, "(none)")
    QLog.spew(s"WEB_BIND_IP: ${env("WEB_BIND_IP")}; WEB_BIND_PORT: ${env("WEB_BIND_PORT")}")
    QLog.spew(s"WEB_HOST: ${env("WEB_HOST")}")
    QLog.spew(s"WEB_OTHER_PORTS: ${env("WEB_OTHER_PORTS")}")
    
    // Tell the QuerkiRoot to initialize and wait for it to be ready. Yes, this is one of those
    // very rare times when we really and for true want to block, because we don't want to consider
    // ourselves truly started until it's done:
    _root = _appSystem.actorOf(Props[QuerkiRoot], "querkiRoot")
    val fut = akka.pattern.ask(_root, QuerkiRoot.Initialize)
    val result = scala.concurrent.Await.result(fut, initTermDuration)
    result match {
      case Initialized(e) => ecology = e
      case _ => QLog.error("Got an unexpected result from QuerkiRoot.Initialize!!!")
    }
    
    // Evil workaround, to give the functional test harness access to the running Ecology:
    QuerkiRoot.ecology = ecology
    
    // Another evil (but temporary) workaround, to make the Ecology available to controllers
    // in the Play 2.4 world:
    controllers.ControllerEcologyHolder.ecology = ecology
    
    QLog.info("Querki has started")
    
    app
  }  
}

object QuerkiApplicationLoader {
  // TODO: this global is evil! How should we expose the ActorSystem to the ShutdownHandler, so that
  // it can shut it all down?
  var _appSystem:ActorSystem = null  
  var _root:ActorRef = null
}

/**
 * Empty trait, so that we have something to inject.
 */
trait ShutdownHandler

/**
 * This is the bit that's actually responsible for shutting the system down at the end. It registers
 * itself with the ApplicationLifecycle, and when the app is shutting down, it terminates the Actors.
 */
@Singleton
class QuerkiShutdownHandler @Inject() (lifecycle: ApplicationLifecycle) extends ShutdownHandler {
  val initTermDuration = 30 seconds
  implicit val initTermTimeout = Timeout(initTermDuration)
  
  QLog.spew("Setting up QuerkiShutdownHandler")
  
  lifecycle.addStopHook { () =>
    QLog.spew("Querki shutting down...")
    
    for {
      termResult <- akka.pattern.ask(QuerkiApplicationLoader._root, QuerkiRoot.Terminate)
      terminated <- QuerkiApplicationLoader._appSystem.terminate()
      _ = QLog.spew("... Done")
    }
      yield ()
  }
}

/**
 * This bit of glue is what causes the QuerkiShutdownHandler to actually get built at the beginning
 * of time, while all the Guice stuff is happening. Note that the config file needs to include:
 * 
 *   play.modules.enabled += "querki.system.QuerkiModule"
 *   
 * Editorial: and that's why I don't like the Guice approach to the world. I appreciate configurability,
 * but *mandating* this incestuous relationship between the config file and the code is idiotic. Is there
 * a way to force Guice to deal with this without config intervention?
 */
class QuerkiModule extends AbstractModule {
  def configure() = {
    bind(classOf[ShutdownHandler]).
      to(classOf[QuerkiShutdownHandler]).asEagerSingleton
  }
}
