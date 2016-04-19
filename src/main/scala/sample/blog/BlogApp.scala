package sample.blog

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorIdentity
import akka.actor.ActorPath
import akka.actor.ActorSystem
import akka.actor.Identify
import akka.actor.Props
import akka.cluster.sharding.{ClusterShardingSettings, ClusterSharding}
import akka.pattern.ask
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.util.Timeout

object BlogApp {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0")) //fonction startup prenant pour parametre une sequence de string definie plus loin, s'il n'y a pas d'argument, on la (la sequence) remplit par defaut avec les arguments listes ici
    else
      startup(args)  //fonction startup prenant pour parametre une sequence de string definie plus loin, s'il ya des arguments, on les lui passe
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port => //pour chaque port de la sequence, faire ce qui est compris dans les accolades {}
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port). //ici on utilise la methode pasreString de ConfigFactory, on analyse la chaine de caractere donnee en parametre et on en deduit une config, les methodes de configfactory ayant parse dans leurs noms creent juste une ConfigValue à partir d'une ressource
        withFallback(ConfigFactory.load()) //laquelle config sera fusionne avec celle dans le configFactory.load car WithFallBack Retourne une nouvelle valeur obtenue en fusionnant la valeur en parametre avec l'autre appellante, avec les keys dans cette valeur l'emportant sur l'autre. l'operation withFallback est utilisee dans la bibliotheque pour fusionner des keys dupliquees dans le meme fichier et de fusionner plusieurs fichiers
                                          //ConfigFactory.Load charge une configuration par defaut, elle est equivalente à load(defaultApplication()) dans la plupart des cas. ConfigFactory Contient les methodes statiques pour creer des instances de Config. Les methodes statiques avec "load" dans leur nom font des sortes d'operations de haut niveau en analysant eventuellement de multiple ressources et en resolvant les substitutions.

      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config) //Utiliser ActorSystem va creer des acteurs top-level, supervises par l'acteur gardien fourni par le systeme d'acteur

      startupSharedJournal(system, startStore = (port == "2551"), path =
        ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))

      val authorListingRegion = ClusterSharding(system).start(
        typeName = AuthorListing.shardName,
        entityProps = AuthorListing.props(),
        settings = ClusterShardingSettings(system),
        extractEntityId = AuthorListing.idExtractor,
        extractShardId = AuthorListing.shardResolver)
      ClusterSharding(system).start(
        typeName = Post.shardName,
        entityProps = Post.props(authorListingRegion),
        settings = ClusterShardingSettings(system),
        extractEntityId = Post.idExtractor,
        extractShardId = Post.shardResolver)

      if (port != "2551" && port != "2552")
        system.actorOf(Props[Bot], "bot")
    }

    def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath): Unit = {
      // Start the shared journal one one node (don't crash this SPOF)
      // This will not be needed with a distributed journal
      if (startStore)
        system.actorOf(Props[SharedLeveldbStore], "store")
      // register the shared journal
      import system.dispatcher
      implicit val timeout = Timeout(15.seconds)
      val f = (system.actorSelection(path) ? Identify(None))
      f.onSuccess {
        case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
        case _ =>
          system.log.error("Shared journal not started at {}", path)
          system.terminate()
      }
      f.onFailure {
        case _ =>
          system.log.error("Lookup of shared journal at {} timed out", path)
          system.terminate()
      }
    }

  }

}

