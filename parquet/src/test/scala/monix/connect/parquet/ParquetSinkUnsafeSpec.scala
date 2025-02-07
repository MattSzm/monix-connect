/*
 * Copyright (c) 2020-2021 by The Monix Connect Project Developers.
 * See the project homepage at: https://connect.monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.connect.parquet

import monix.eval.Task
import monix.execution.Scheduler

import java.io.File
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.testing.scalatest.MonixTaskSpec
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.hadoop.ParquetWriter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpecLike, AsyncWordSpec}
import org.mockito.IdiomaticMockito
import org.mockito.MockitoSugar.when

import scala.concurrent.duration._
import scala.util.Failure

class ParquetSinkUnsafeSpec
  extends AsyncWordSpec with MonixTaskSpec with IdiomaticMockito with Matchers with AvroParquetFixture
  with BeforeAndAfterAll {

  override implicit val scheduler: Scheduler = Scheduler.io("parquet-sync-unsafe-spec")
  override def afterAll(): Unit = {
    import scala.reflect.io.Directory
    val directory = new Directory(new File(folder))
    directory.deleteRecursively()
  }

  s"$ParquetSink" should {

    "unsafe write avro records in parquet" in {
      val n: Int = 2
      val filePath: String = genFilePath()
      val records: List[GenericRecord] = genAvroUsers(n).sample.get.map(personToRecord)
      val w: ParquetWriter[GenericRecord] = parquetWriter(filePath, conf, schema)

      Observable
        .fromIterable(records)
        .consumeWith(ParquetSink.fromWriterUnsafe(w)) >>
        Task.eval(fromParquet[GenericRecord](filePath, conf, avroParquetReader(filePath, conf))).asserting {
          parquetContent =>
            parquetContent.length shouldEqual n
            parquetContent should contain theSameElementsAs records
        }
    }

    "materialises to 0 when an empty observable is passed" in {
      val filePath: String = genFilePath()
      val writer: ParquetWriter[GenericRecord] = parquetWriter(filePath, conf, schema)

      for {
        writtenRecords <- Observable
          .empty[GenericRecord]
          .consumeWith(ParquetSink.fromWriterUnsafe(writer))
        file = new File(filePath)
        parquetContent = fromParquet[GenericRecord](filePath, conf, avroParquetReader(filePath, conf))
      } yield {
        writtenRecords shouldBe 0
        file.exists() shouldBe true
        parquetContent.length shouldEqual 0
      }

    }

    "signals error when a malformed parquet writer was passed" in {
      val n = 1
      val filePath: String = genFilePath()
      val records: List[GenericRecord] = genAvroUsers(n).sample.get.map(personToRecord)

      Observable
        .fromIterable(records)
        .consumeWith(ParquetSink.fromWriterUnsafe(null))
        .attempt
        .asserting { writeAttempt =>
          writeAttempt.isLeft shouldBe true
          val file = new File(filePath)
          file.exists() shouldBe false
        }
    }

    "signals error when the underlying parquet writer throws an error" in {
      val testScheduler = TestScheduler()
      val filePath: String = genFilePath()
      val record: GenericRecord = personToRecord(genPerson.sample.get)
      val ex = DummyException("Boom!")
      val parquetWriter = mock[ParquetWriter[GenericRecord]]
      when(parquetWriter.write(record)).thenThrow(ex)

      Observable
        .now(record)
        .consumeWith(ParquetSink.fromWriterUnsafe(parquetWriter))
        .attempt
        .asserting { writeAttempt =>
          writeAttempt.isLeft shouldBe true
          val file = new File(filePath)
          file.exists() shouldBe false
        }
    }

  }

}
