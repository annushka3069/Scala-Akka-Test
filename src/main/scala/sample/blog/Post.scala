package sample.blog

import scala.concurrent.duration._ //pour la durée
import akka.actor.ActorLogging //pour la journalisation
import akka.actor.ActorRef // reference d'acteur
import akka.actor.PoisonPill //message provoquant l'arrêt de l'acteur lorsque traité, sera dans la file com les autres messages qu'il y trouve et ne sera traité qu'après eux
import akka.actor.Props //classe de configuration pour spécifier les options de création d'une classe
import akka.actor.ReceiveTimeout//délai d'inactivité après lequel on envoie un message de receive timeout
import akka.cluster.sharding.ShardRegion //Cet acteur crée des acteurs enfants de l'entité sur la demande des shards dont il est  responsable. Il délègue les messages destinés aux autres shards à l'acteur responsable de la ShardRegion sur les autres nœuds.
import akka.cluster.sharding.ShardRegion.Passivate // ??????????
import akka.persistence.PersistentActor //Akka persistance permet aux acteurs stateful de sauvegarder leur état interne de sorte qu'il puisse être récupéré lorsqu' un acteur est démarré, redémarré après un crash JVM ou par un superviseur, ou migré dans un cluster. Le concept clé derrière la persistance d'Akka  est que seuls les changements à l'état interne d'un acteur sont conservés mais jamais son état actuel directement (excepté pour les instantanés  optionnels). 

object Post {

  def props(authorListing: ActorRef): Props =
    Props(new Post(authorListing))

  object PostContent {
    val empty = PostContent("", "", "") //classe PostContent déclarée sans le new car c'est une case class donc statique
  }
  case class PostContent(author: String, title: String, body: String)

  sealed trait Command {
    def postId: String
  }
  case class AddPost(postId: String, content: PostContent) extends Command // implémentent le trait Command
  case class GetContent(postId: String) extends Command
  case class ChangeBody(postId: String, body: String) extends Command
  case class Publish(postId: String) extends Command

  sealed trait Event
  case class PostAdded(content: PostContent) extends Event
  case class BodyChanged(body: String) extends Event
  case object PostPublished extends Event

  val idExtractor: ShardRegion.ExtractEntityId = { //Interface de la fonction partielle utilisée par la ShardRegion pour extraire à partir d'un message entrant l'id de l'entité et le message à envoyer à l'entité. L'implémentation est une application spécifique, si la fonction partielle ne correspond pas, le message sera unhandled, à savoir posté comme messages Unhandled sur le flux d'événements.
    case cmd: Command => (cmd.postId, cmd)
  }

  val shardResolver: ShardRegion.ExtractShardId = { //Interface de la fonction utilisée par le ShardRégion pour extraire l'identifiant de Shard à partir d'un message entrant. Seuls les messages qui ont passé l'ExtractEntityId seront utilisés comme argument pour cette fonction.
    case cmd: Command => (math.abs(cmd.postId.hashCode) % 100).toString
  }

  val shardName: String = "Post"

  private case class State(content: PostContent, published: Boolean) {

    def updated(evt: Event): State = evt match {
      case PostAdded(c)   => copy(content = c) //Une méthode nommée copy est implicitement ajoutée à chaque case class à moins que la classe ait déja un membre (directement definie ou héritée) avec ce nom, ou que la classe ait un paramètre répété.
      case BodyChanged(b) => copy(content = content.copy(body = b))
      case PostPublished  => copy(published = true)
    }
  }
}

class Post(authorListing: ActorRef) extends PersistentActor with ActorLogging {

  import Post._

  // self.path.parent.name is the type name (utf-8 URL-encoded) 
  // self.path.name is the entry identifier (utf-8 URL-encoded)
  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private var state = State(PostContent.empty, false)

  override def receiveRecover: Receive = {
    case evt: PostAdded =>
      context.become(created)
      state = state.updated(evt)
    case evt @ PostPublished =>
      context.become(published)
      state = state.updated(evt)
    case evt: Event => state =
      state.updated(evt)
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case GetContent(_) => sender() ! state.content
    case AddPost(_, content) =>
      if (content.author != "" && content.title != "")
        persist(PostAdded(content)) { evt =>
          state = state.updated(evt)
          context.become(created)
          log.info("New post saved: {}", state.content.title)
        }
  }

  def created: Receive = {
    case GetContent(_) => sender() ! state.content
    case ChangeBody(_, body) =>
      persist(BodyChanged(body)) { evt =>
        state = state.updated(evt)
        log.info("Post changed: {}", state.content.title)
      }
    case Publish(postId) =>
      persist(PostPublished) { evt =>
        state = state.updated(evt)
        context.become(published)
        val c = state.content
        log.info("Post published: {}", c.title)
        authorListing ! AuthorListing.PostSummary(c.author, postId, c.title)
      }
  }

  def published: Receive = {
    case GetContent(_) => sender() ! state.content
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

}
