package domaindocs4s.architecture.lineage.example

import doobie.*
import doobie.implicits.*
import cats.effect.IO
import io.grpc.Metadata
import domaindocs4s.architecture.lineage.example.grpc.user_service as userGrpc
import domaindocs4s.architecture.lineage.example.grpc.rate_service as rateGrpc

// ============================================================================
// Example service layers with real doobie + fs2-grpc for TASTy scanning.
//
// Architecture: UserGrpcApi -> UserService -> UserRepo -> Database
//               UserGrpcApi -> RateServiceFs2Grpc (gRPC client)
//
// UserRepo uses real doobie: sql"..." interpolation, .query[T].unique,
// .update.run, etc. The TASTy scanner detects these patterns.
// UserGrpcApi implements UserServiceFs2Grpc (server) and calls
// RateServiceFs2Grpc (client). The gRPC scanner detects these patterns.
// ============================================================================

// ── Domain types ─────────────────────────────────────────────────────────────

case class Transaction(id: Long, userId: Long, amount: BigDecimal, description: String)
object Transaction {
  given Read[Transaction] = Read.derived
  given Write[Transaction] = Write.derived
}

// ============================================================================
// Service layers — real method bodies that produce inspectable TASTy
// ============================================================================

/** Repository — direct database access via doobie queries. */
class UserRepo {

  def getBalance(userId: Long): ConnectionIO[BigDecimal] =
    sql"SELECT balance FROM users WHERE id = $userId".query[BigDecimal].unique

  def getTransactions(userId: Long): ConnectionIO[List[Transaction]] =
    sql"SELECT id, user_id, amount, description FROM transactions WHERE user_id = $userId"
      .query[Transaction].to[List]

  def insertTransaction(tx: Transaction): ConnectionIO[Int] =
    sql"INSERT INTO transactions (user_id, amount, description) VALUES (${tx.userId}, ${tx.amount}, ${tx.description})"
      .update.run

  def updateBalance(userId: Long, newBalance: BigDecimal): ConnectionIO[Int] =
    sql"UPDATE users SET balance = $newBalance WHERE id = $userId"
      .update.run
}

/** Service — business logic, orchestrates repository calls. */
class UserService(val repo: UserRepo) {

  def getBalance(userId: Long): ConnectionIO[BigDecimal] =
    repo.getBalance(userId)

  def deposit(userId: Long, amount: BigDecimal): ConnectionIO[Unit] =
    for {
      _ <- repo.updateBalance(userId, amount)
      _ <- repo.insertTransaction(Transaction(0, userId, amount, "deposit"))
    } yield ()

  def getHistory(userId: Long): ConnectionIO[List[Transaction]] =
    repo.getTransactions(userId)
}

/** Event publisher — publishes domain events to Kafka.
  *
  * The actual Kafka interaction is library-specific (fs2-kafka, pekko-kafka, etc.)
  * and can't be auto-detected from TASTy. Use LineageAdjustments to declare the
  * Kafka integration:
  *   LineageAdjustments.builder
  *     .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
  *     .build
  */
class EventPublisher {
  def publishDeposit(@scala.annotation.unused userId: Long, @scala.annotation.unused amount: BigDecimal): IO[Unit] = IO.unit
}

/** gRPC API — entry point, delegates to service.
  *
  * Implements UserServiceFs2Grpc (server exposure) and consumes
  * RateServiceFs2Grpc (client usage). The gRPC scanner detects both.
  */
class UserGrpcApi(
    val service: UserService,
    val rateClient: rateGrpc.RateServiceFs2Grpc[IO, Metadata],
    val publisher: EventPublisher,
    xa: Transactor[IO],
) extends userGrpc.UserServiceFs2Grpc[IO, Metadata] {

  def getBalance(request: userGrpc.GetBalanceRequest, ctx: Metadata): IO[userGrpc.GetBalanceResponse] =
    service.getBalance(request.userId).transact(xa)
      .map(b => userGrpc.GetBalanceResponse(b.toString))

  def deposit(request: userGrpc.DepositRequest, ctx: Metadata): IO[userGrpc.DepositResponse] =
    for {
      rate <- rateClient.getRate(rateGrpc.GetRateRequest(request.currency), ctx)
      _    <- service.deposit(request.userId, BigDecimal(request.amount)).transact(xa)
      _    <- publisher.publishDeposit(request.userId, BigDecimal(request.amount))
    } yield userGrpc.DepositResponse(true)

  def getHistory(request: userGrpc.GetHistoryRequest, ctx: Metadata): IO[userGrpc.GetHistoryResponse] =
    service.getHistory(request.userId).transact(xa)
      .map(txs => userGrpc.GetHistoryResponse(txs.map(t =>
        userGrpc.TransactionProto(t.id, t.userId, t.amount.toString, t.description))))
}
