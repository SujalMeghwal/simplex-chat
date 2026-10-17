{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE FlexibleInstances #-}
{-# LANGUAGE InstanceSigs #-}
{-# LANGUAGE RankNTypes #-}
{-# LANGUAGE TupleSections #-}
{-# LANGUAGE TypeApplications #-}
{-# OPTIONS_GHC -Wno-orphans #-}

module Simplex.Chat.Util (week, encryptFile, hashFile, digestAndHashFile, chunkSize, liftIOEither, shuffle, zipWith3') where

import Control.Exception (Exception)
import Control.Monad
import Control.Monad.Except
import Control.Monad.IO.Class
import Control.Monad.IO.Unlift (MonadUnliftIO (..))
import Control.Monad.Reader
import Crypto.Hash (Context, Digest, SHA256, SHA512, hashFinalize, hashInit, hashUpdate)
import qualified Crypto.MAC.HMAC as HMAC
import Data.Bifunctor (first)
import qualified Data.ByteArray as BA
import Data.ByteArray.Encoding (Base (Base16), convertToBase)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as LB
import Data.List (foldl', sortBy)
import Data.List.NonEmpty (NonEmpty (..))
import Data.Ord (comparing)
import Data.Text (Text)
import Data.Text.Encoding (decodeLatin1)
import Data.Time (NominalDiffTime)
import Data.Word (Word16)
import qualified Simplex.FileTransfer.Description as FD
import Simplex.Messaging.Crypto.File (CryptoFile (..), CryptoFileArgs (..))
import qualified Simplex.Messaging.Crypto.File as CF
import System.Random (randomRIO)
import qualified UnliftIO.Exception as E
import UnliftIO.IO (IOMode (..), withFile)

week :: NominalDiffTime
week = 7 * 86400

encryptFile :: FilePath -> FilePath -> CryptoFileArgs -> ExceptT String IO ()
encryptFile fromPath toPath cfArgs = do
  let toFile = CryptoFile toPath $ Just cfArgs
  -- uncomment to test encryption error in runTestFileTransferEncrypted
  -- throwError "test error"
  withExceptT show $
    withFile fromPath ReadMode $ \r -> CF.withFile toFile WriteMode $ \w -> do
      encryptChunks r w
      liftIO $ CF.hPutTag w
  where
    encryptChunks r w = do
      ch <- liftIO $ LB.hGet r chunkSize
      unless (LB.null ch) $ liftIO $ CF.hPut w ch
      unless (LB.length ch < chunkSize) $ encryptChunks r w

-- HMAC-SHA256 (keyed with a random per-profile secret, see Store.Files.getOrCreateHashKey) of a
-- file's plaintext content, used only for local dedup (file_hash column) -- never transmitted,
-- never part of any protocol message. CF.readFile transparently decrypts when cfArgs is Just, so
-- this always hashes content, not ciphertext, regardless of whether privacyEncryptLocalFiles is
-- on. Keyed rather than a plain hash so an attacker who has a precomputed hash of some known
-- file (e.g. a leaked/public file) can't test it against this profile without the local key --
-- a plain SHA-256 would let them confirm "this profile has file X" without ever reading it.
-- One read of a received file for both the sender-declared digest check (SHA512, same as
-- Internal.cryptoFileDigest) and the local dedup HMAC (same as hashFile). Both are updated per chunk
-- in one pass, so the content is streamed rather than kept in memory for a second consumer.
digestAndHashFile :: BS.ByteString -> CryptoFile -> ExceptT String IO (FD.FileDigest, Text)
digestAndHashFile hmacKey cf = do
  content <- withExceptT show $ CF.readFile cf
  let step (!h, !m) c = (hashUpdate h c, HMAC.update m c)
      (sha, mac) = foldl' step (hashInit :: Context SHA512, HMAC.initialize hmacKey :: HMAC.Context SHA256) (LB.toChunks content)
  pure (FD.FileDigest $ BA.convert (hashFinalize sha :: Digest SHA512), hmacDigestHex (HMAC.hmacGetDigest $ HMAC.finalize mac))

hashFile :: BS.ByteString -> FilePath -> Maybe CryptoFileArgs -> ExceptT String IO Text
hashFile hmacKey path cfArgs = do
  hmacHex hmacKey <$> withExceptT show (CF.readFile (CryptoFile path cfArgs))

hmacHex :: BS.ByteString -> LB.ByteString -> Text
hmacHex hmacKey content =
  let ctx = HMAC.updates (HMAC.initialize hmacKey :: HMAC.Context SHA256) (LB.toChunks content)
   in hmacDigestHex (HMAC.hmacGetDigest $ HMAC.finalize ctx)

hmacDigestHex :: Digest SHA256 -> Text
hmacDigestHex digest = decodeLatin1 (convertToBase Base16 digest :: BS.ByteString)

chunkSize :: Num a => a
chunkSize = 65536
{-# INLINE chunkSize #-}

shuffle :: [a] -> IO [a]
shuffle xs = map snd . sortBy (comparing fst) <$> mapM (\x -> (,x) <$> random) xs
  where
    random :: IO Word16
    random = randomRIO (0, 65535)

zipWith3' :: (a -> b -> c -> d) -> NonEmpty a -> NonEmpty b -> NonEmpty c -> NonEmpty d
zipWith3' f ~(x :| xs) ~(y :| ys) ~(z :| zs) = f x y z :| zipWith3 f xs ys zs

liftIOEither :: (MonadIO m, MonadError e m) => IO (Either e a) -> m a
liftIOEither a = liftIO a >>= liftEither
{-# INLINE liftIOEither #-}

newtype InternalException e = InternalException {unInternalException :: e}
  deriving (Eq, Show)

instance Exception e => Exception (InternalException e)

instance Exception e => MonadUnliftIO (ExceptT e IO) where
  {-# INLINE withRunInIO #-}
  withRunInIO :: ((forall a. ExceptT e IO a -> IO a) -> IO b) -> ExceptT e IO b
  withRunInIO inner =
    ExceptT . fmap (first unInternalException) . E.try $
      withRunInIO $ \run ->
        inner $ run . (either (E.throwIO . InternalException) pure <=< runExceptT)

instance Exception e => MonadUnliftIO (ExceptT e (ReaderT r IO)) where
  {-# INLINE withRunInIO #-}
  withRunInIO :: ((forall a. ExceptT e (ReaderT r IO) a -> IO a) -> IO b) -> ExceptT e (ReaderT r IO) b
  withRunInIO inner =
    withExceptT unInternalException . ExceptT . E.try $
      withRunInIO $ \run ->
        inner $ run . (either (E.throwIO . InternalException) pure <=< runExceptT)
