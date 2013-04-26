/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.ingest
package service

import com.precog.common.Path
import com.precog.common.client._
import com.precog.common.ingest._
import com.precog.common.jobs._
import com.precog.common.security._
import com.precog.common.services.ServiceLocation

import blueeyes.core.data.ByteChunk
import blueeyes.core.http._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.http.HttpHeaders._
import blueeyes.core.service._
import blueeyes.json._
import blueeyes.util.Clock

import akka.dispatch.Future
import akka.dispatch.Promise
import akka.dispatch.ExecutionContext
import akka.util.Timeout

import scalaz._
import scalaz.EitherT._
import scalaz.std.string._
import scalaz.std.option._
import scalaz.syntax.monad._
import scalaz.syntax.semigroup._
import com.weiglewilczek.slf4s._


class FileCreateHandler(serviceLocation: ServiceLocation, jobManager: JobManager[Response], clock: Clock, eventStore: EventStore[Future], ingestTimeout: Timeout)(implicit M: Monad[Future], executor: ExecutionContext) extends CustomHttpService[ByteChunk, (APIKey, Path) => Future[HttpResponse[JValue]]] with Logging {
  private val baseURI = serviceLocation.toURI

  val service: HttpRequest[ByteChunk] => Validation[NotServed, (APIKey, Path) => Future[HttpResponse[JValue]]] = (request: HttpRequest[ByteChunk]) => Success {
    (apiKey: APIKey, path: Path) => {
      val timestamp = clock.now()

      request.content map { content =>
      // TODO: check ingest permissions
        (for {
          jobId <- jobManager.createJob(apiKey, "ingest-" + path, "ingest", None, Some(timestamp)).map(_.id)
          bytes <- right(ByteChunk.forceByteArray(content))
          storeFile = StoreFile(apiKey, path, jobId, FileContent(bytes, RawUTF8Encoding), timestamp.toInstant, None, StoreMode.Create)
          _ <- right(eventStore.save(storeFile, ingestTimeout))
        } yield {
          val resultsPath = (baseURI.path |+| Some("/data/fs/" + path.path)).map(_.replaceAll("//", "/"))
          val locationHeader = Location(baseURI.copy(path = resultsPath))
          HttpResponse[JValue](Accepted, headers = HttpHeaders(List(locationHeader)))
        }) valueOr { errors =>
          logger.error("File creation failed due to errors in job service: " + errors)
          HttpResponse[JValue](HttpStatus(InternalServerError, "An error occurred connecting to job tracking service."))
        }
      } getOrElse {
        Promise successful HttpResponse[JValue](HttpStatus(BadRequest, "Attempt to create a file without body content."))
      }
    }
  }

  val metadata = NoMetadata
}





