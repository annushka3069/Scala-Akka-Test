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

  def props(authorListing: ActorRef): Props = //Une reference d'acteur est un sous-type d'ActorRef dont le principal rôle est d'assurer l'envoi de messages à l'acteur qu'il représente.
    Props(new Post(authorListing))

  object PostContent {
    val empty = PostContent("", "", "") //classe PostContent déclarée sans le new car c'est une case class donc statique
  }
  case class PostContent(author: String, title: String, body: String)

  sealed trait Command {
    def postId: String
  }
  case class AddPost(postId: String, content: PostContent) extends Command // implémentent le trait Command, ajouter un post
  case class GetContent(postId: String) extends Command //avoir le contenu d'un post via son id
  case class ChangeBody(postId: String, body: String) extends Command //changer le contenu d'un post
  case class Publish(postId: String) extends Command //publier un post via son id

  sealed trait Event
  case class PostAdded(content: PostContent) extends Event //evenement postajoute prend un contenu de type postcontent avec lauteur le titre et le corps du post
  case class BodyChanged(body: String) extends Event //evenement corps change prenant en parametre le nouveau corps de type string 
  case object PostPublished extends Event //evenement postpublie

  val idExtractor: ShardRegion.ExtractEntityId = { //Interface de la fonction partielle utilisée par la ShardRegion pour extraire à partir d'un message entrant l'id de l'entité et le message à envoyer à l'entité. L'implémentation est une application spécifique, si la fonction partielle ne correspond pas, le message sera unhandled, à savoir posté comme messages Unhandled sur le flux d'événements.
    case cmd: Command => (cmd.postId, cmd) // le post id pour l'identifiant de l'entite et le cmd pr le msg à lui envoyer
  }

  val shardResolver: ShardRegion.ExtractShardId = { //Interface de la fonction utilisée par le ShardRégion pour extraire l'identifiant de Shard à partir d'un message entrant. Seuls les messages qui ont passé l'ExtractEntityId seront utilisés comme argument pour cette fonction.
    case cmd: Command => (math.abs(cmd.postId.hashCode) % 100).toString //fonction de hachage pour extraire lid de shard a partir de lid de lentite
  }

  val shardName: String = "Post" //shardname de type string

  private case class State(content: PostContent, published: Boolean) { //represente letat, les différents changements detat qui surviennent suite aux evenement sont repertoriés ici

    def updated(evt: Event): State = evt match { //definition de type etat prenant en param un evenement dont on check le match --- Une méthode nommée copy est implicitement ajoutée à chaque case class à moins que la classe ait déja un membre (directement definie ou héritée) avec ce nom, ou que la classe ait un paramètre répété.
      case PostAdded(c)   => copy(content = c) // en cas djaout de post, mettre content a ce post la
      case BodyChanged(b) => copy(content = content.copy(body = b))//en cas de changement de corps, mettre le corps du content a jour
      case PostPublished  => copy(published = true)// en cas de publication de post, mettre published a true
    }
  }
}

class Post(authorListing: ActorRef) extends PersistentActor with ActorLogging {//Acteur persistant et  capable de conserver les events dans un journal et d'y réagir d'un façon thread-safe. Il peut être utilisé pour implémenter à la fois les acteurs command et event sourced. Quand un acteur persistant est lancé ou relancé,les messages journalisés lui sont renvoyés afin qu'il puisse retrouver son état interne à partir de ces messages.


  import Post._

  // self.path.parent.name is the type name (utf-8 URL-encoded) 
  // self.path.name is the entry identifier (utf-8 URL-encoded)
  // Chaque acteur a acces à sa reference locale via le champ self; cette reference est egalement incluse comme reference sender par defaut pour tous les messages envoyés aux autres acteurs.
  //Reciproquement, durant le traitement des message, l'acteur a acces a une reference representant le sender du message en cours via la methode sender.
  override def persistenceId: String = self.path.parent.name + "-" + self.path.name //Un acteur persitant doit avoir un id qui ne change pas au fil des differentes incarnations d'acteur. L'id doit être defini avec la methode persistenceId. The persistenceId identifie l'acteur persistant à partir duquel la vue reçoit les messages journalises. Il n'est pas necessaire que l'acteur persistant reference soit vraiment en cours dexecution.Les Vues lisent les messages directement a partir du journal d'un acteur persistant. Quand un acteur persistant est demarre plus tard et commence a ecrire de nouveaux messages, par defaut, la vue correspondante est automatiquement mise a jour.


  // passivate the entity when no activity
  //context expose des infos contextuelles pour l'acteur et le message courant, telles que: – les methodes factory pour creer des child actors (actorOf)–system auquel appartient l'acteur–superviseur parent –les enfants supervises–Vous pouvez importer les membres de context pour eviter de prefixer les access avec context.
  context.setReceiveTimeout(2.minutes)

  private var state = State(PostContent.empty, false) //on initialise letat avec un contenu de poste vide, empty qu'on a defini plus haut et un published false

  override def receiveRecover: Receive = { //La methode receiveRecover des acteurs persistants definit comment l'etat est mis à jour durant la recuperation(recovery, replaying) en gerant les messages Evt et SnapshotOffer.
    case evt: PostAdded =>
      context.become(created)
      state = state.updated(evt)
    case evt @ PostPublished =>
      context.become(published)
      state = state.updated(evt)
    case evt: Event => state =
      state.updated(evt)
  }

  override def receiveCommand: Receive = initial //La methode receiveCommand de l'acteur persistant est un command handler. Dans cet exemple, une commande est geree en generant deux evenements qui sont persisted et handled. Les Events sont persisted en appelant la methode persist avec un evenement (ou une sequence devents) comme premier argument et un event handler comme second argument.

  def initial: Receive = {
    case GetContent(_) => sender() ! state.content //sil sagit dune commande pour obtenir le contenu, retourner a lexpediteur un message contenant le state.content
    case AddPost(_, content) => //commande pour ajouter un post, avec precision du contenu
      if (content.author != "" && content.title != "") //si lauteur de ce post ainsi que son titre ne sont pas vides
        persist(PostAdded(content)) { evt => //persister levenement postadded et mettre a jour letat de lacteur persistant
          state = state.updated(evt)
          context.become(created)//modifier le comportement de lacteur a created 
          log.info("New post saved: {}", state.content.title) //afficher pour informer de la creation dun nouveau post en donnant le titre
        }
  }

  def created: Receive = {
    case GetContent(_) => sender() ! state.content //sil sagit dune commande pour obtenir le contenu, retourner a lexpediteur un message contenant le state.content
    case ChangeBody(_, body) =>  //sil sagit dune commande pour changer le contenu,
      persist(BodyChanged(body)) { evt => // persister levenement bodychanged en precisant le nouveau contenu 
        state = state.updated(evt) //et mettre a jour letat de lacteur persistant
        log.info("Post changed: {}", state.content.title) //afficher pr informer du changemen de contenu en precisant le titre du post concerne
      }
    case Publish(postId) => //sil sagit dune commande pour publier un post, 
      persist(PostPublished) { evt => //persister levenement postpublished
        state = state.updated(evt) //et mettre a jour letat de lacteur persistant
        context.become(published) //modifier le comportement de lacteur a published 
        val c = state.content //creation dun valeur c de type postcontent, correspondant au content du state
        log.info("Post published: {}", c.title) //afficher pr informer de la publication de post en precisant le titre du post correspondant
        authorListing ! AuthorListing.PostSummary(c.author, postId, c.title)
      }
  }

  def published: Receive = {
    case GetContent(_) => sender() ! state.content //sil sagit dune commande pour obtenir le contenu, retourner a lexpediteur un message contenant le state.content
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

}