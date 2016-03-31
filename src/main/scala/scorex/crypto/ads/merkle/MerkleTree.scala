package scorex.crypto.ads.merkle

import scorex.crypto.ads.StorageType
import scorex.crypto.hash.CryptographicHash
import scorex.utils.ScryptoLogging

import scala.annotation.tailrec

trait MerkleTree[HashFn <: CryptographicHash, ST <: StorageType] extends ScryptoLogging {
  import MerkleTree._

  type Digest = HashFn#Digest

  val hashFunction: HashFn

  val storage: TreeStorage[HashFn, ST]

  val nonEmptyBlocks: Position

  protected lazy val emptyHash = hashFunction(Array[Byte]())

  lazy val level = calculateRequiredLevel(nonEmptyBlocks)
  lazy val rootHash: Digest = getHash((level, 0)).get

  storage.commit()

  /**
    * Return AuthDataBlock at position $index
    */
  def proofByIndex(index: Position): Option[MerklePath[HashFn]] = {
    if (index < nonEmptyBlocks && index >= 0) {
      @tailrec
      def calculateTreePath(n: Position, currentLevel: Int, acc: Seq[Digest] = Seq()): Seq[Digest] = {
        if (currentLevel < level) {
          val hashOpt = if (n % 2 == 0) getHash((currentLevel, n + 1)) else getHash((currentLevel, n - 1))
          hashOpt match {
            case Some(h) =>
              calculateTreePath(n / 2, currentLevel + 1, h +: acc)
            case None if currentLevel == 0 && index == nonEmptyBlocks - 1 =>
              calculateTreePath(n / 2, currentLevel + 1, emptyHash +: acc)
            case None =>
              log.error(s"Unable to get hash for lev=$currentLevel, position=$n")
              acc.reverse
          }
        } else {
          acc.reverse
        }
      }
      Some(MerklePath(index, calculateTreePath(index, 0)))
    } else {
      None
    }
  }

  private def getHash(key: TreeStorage.Key): Option[Digest] = {
    storage.get(key) match {
      case None =>
        if (key._1 > 0) {
          val h1 = getHash((key._1 - 1, key._2 * 2))
          val h2 = getHash((key._1 - 1, key._2 * 2 + 1))
          val calculatedHash = (h1, h2) match {
            case (Some(hash1), Some(hash2)) => hashFunction(hash1 ++ hash2)
            case (Some(h), _) => hashFunction(h ++ emptyHash)
            case (_, Some(h)) => hashFunction(emptyHash ++ h)
            case _ => emptyHash
          }
          storage.set(key, calculatedHash)
          Some(calculatedHash)
        } else {
          None
        }
      case digest =>
        digest
    }
  }
}

object MerkleTree {
  def calculateRequiredLevel(numberOfDataBlocks: Position): Int = {
    def log2(x: Double): Double = math.log(x) / math.log(2)
    math.ceil(log2(numberOfDataBlocks)).toInt
  }
}