package monix.connect.sqs

import monix.execution.Scheduler.Implicits.global
import monix.connect.sqs.producer.{FifoMessage, StandardMessage}
import monix.connect.sqs.domain.{QueueName, QueueUrl}
import monix.eval.Task
import org.scalacheck.Gen
import org.scalatest.{BeforeAndAfterEach, TestSuite}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._

import java.net.URI

trait SqsITFixture {
  this: TestSuite with BeforeAndAfterEach =>

  override def beforeEach(): Unit = {
    val deleteAll = sqsClient.operator.listQueueUrls().mapEvalF(sqsClient.operator.deleteQueue).completedL.attempt
    val deleteQueue = sqsClient.operator.getQueueUrl(queueName).flatMap(sqsClient.operator.deleteQueue).attempt
    val deleteFifoQueue = sqsClient.operator.getQueueUrl(fifoQueueName).flatMap(sqsClient.operator.deleteQueue).attempt
    Task.parZip3(deleteQueue, deleteFifoQueue, deleteAll).runSyncUnsafe()
  }

  def dlqRedrivePolicyAttr(dlQueueArn: String) = Map(QueueAttributeName.REDRIVE_POLICY -> s"""{"maxReceiveCount":"1", "deadLetterTargetArn": "$dlQueueArn" }""")

  val invalidRequestErrorMsg: String = """Invalid request""".stripMargin

  val defaultAwsCredProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x"))
  val asyncClient =
    SqsAsyncClient
      .builder
      .credentialsProvider(defaultAwsCredProvider)
      .endpointOverride(new URI("http://localhost:9324"))
      .region(Region.US_EAST_1)
      .build

  implicit val sqsClient: Sqs = Sqs.createUnsafe(asyncClient)

  val fifoDeduplicationQueueAttr = Map(
    QueueAttributeName.FIFO_QUEUE -> "true",
    QueueAttributeName.CONTENT_BASED_DEDUPLICATION -> "true")

  val genQueueName: Gen[QueueName] = Gen.identifier.map(id => QueueName("queue-" + id.take(30)))
  // it must end with `.fifo` prefix, see https://github.com/aws/aws-sdk-php/issues/1331
  val genFifoQueueName: Gen[QueueName] = Gen.identifier.map(id => QueueName("queue-" + id.take(20) + ".fifo"))

  def queueUrlPrefix(queueName: String) = s"http://localhost:9324/000000000000/${queueName}"

  val queueName: QueueName = QueueName("queue-1")

  // it must end with `.fifo` prefix, see https://github.com/aws/aws-sdk-php/issues/1331
  val fifoQueueName: QueueName =  QueueName("queue122315141-1.fifo")
  val genGroupId: Gen[String] = Gen.identifier.map(_.take(10))
  val genId: Gen[String] = Gen.identifier.map(_.take(15))
  val defaultGroupId: String = genGroupId.sample.get
  val genNamePrefix: Gen[String] = Gen.nonEmptyListOf(Gen.alphaChar).map(chars => "test-" + chars.mkString.take(20))
  val genMessageId: Gen[String] = Gen.nonEmptyListOf(Gen.alphaChar).map(chars => "msg-" + chars.mkString.take(5))
  val genReceiptHandle: Gen[String] =
    Gen.nonEmptyListOf(Gen.alphaChar).map(chars => "rHandle-" + chars.mkString.take(10))
  val genMessageBody: Gen[String] = Gen.nonEmptyListOf(Gen.alphaChar).map(chars => "body-" + chars.mkString.take(200))
  def genFifoMessageWithDeduplication(groupId: String = defaultGroupId): Gen[FifoMessage] =
    Gen.identifier.map(_.take(10)).map(id => FifoMessage(id, groupId = groupId, deduplicationId = Some(id)))

  def genFifoMessage(groupId: String = defaultGroupId, deduplicationId: Option[String] = None): Gen[FifoMessage] = Gen.identifier.map(_.take(10)).map(id => FifoMessage(id, groupId = groupId, deduplicationId = deduplicationId))
  val genQueueUrl: Gen[QueueUrl] = QueueUrl(genId.sample.get)

  val genStandardMessage: Gen[StandardMessage] = Gen.identifier.map(_.take(12)).map(StandardMessage(_))

  val message: Gen[Message] = for {
    id      <- genMessageId
    rhandle <- genReceiptHandle
    body    <- genMessageBody
  } yield Message.builder.messageId(id).receiptHandle(rhandle).body(body).build()

}
