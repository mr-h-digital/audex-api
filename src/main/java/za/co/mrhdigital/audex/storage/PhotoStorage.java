package za.co.mrhdigital.audex.storage;

import java.util.Optional;

/** Where audit photos are kept. The production implementation is the Railway storage bucket (S3 compatible). */
public interface PhotoStorage {

    record StoredPhoto(byte[] bytes, String contentType) {}

    void put(String key, byte[] bytes, String contentType);

    Optional<StoredPhoto> get(String key);
}
