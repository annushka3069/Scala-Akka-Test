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


import scala.collection.JavaConversions._
import java.net.NetworkInterface

object HostIP {

  /**
   * @return the ip adress if it's a local adress (172.16.xxx.xxx, 172.31.xxx.xxx , 192.168.xxx.xxx, 10.xxx.xxx.xxx)
   */
  def load(): Option[String] = {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    val interface = interfaces find (_.getName equals "eth0")

    interface flatMap { inet =>
      // the docker adress should be siteLocal
      inet.getInetAddresses find (_ isSiteLocalAddress) map (_ getHostAddress)
    }
  }
}


object BlogApp {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "2550"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port =>
      // Override the configuration of the ip
      val name = HostIP.load.get //config getString("clustering.ip")
      println("\n\n ***********************: Adress IP : " + name )
     
    import com.typesafe.config.ConfigResolveOptions
     val config = ConfigFactory.load(
      getClass.getClassLoader,
      ConfigResolveOptions.defaults.setAllowUnresolved(true)
    )
    val seedNodesString =s"clustering.ip=$name"
// build the final config and resolve it
    (ConfigFactory parseString seedNodesString)
//      .withFallback(ConfigFactory parseResources configPath)
      .withFallback(config)
      .resolve



      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)


      startupSharedJournal(system, startStore = (port == s"$port"), path =
        ActorPath.fromString(s"akka.tcp://ClusterSystem@$name:$port/user/store"))

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

      if (port != "2551" )
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
