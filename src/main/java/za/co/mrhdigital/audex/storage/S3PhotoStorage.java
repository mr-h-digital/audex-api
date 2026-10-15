package za.co.mrhdigital.audex.storage;

import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Railway buckets speak the S3 API; this is only created when a bucket is configured (BUCKET env var). */
@Component
@ConditionalOnExpression("!'${audex.storage.bucket:}'.isEmpty()")
public class S3PhotoStorage implements PhotoStorage {

    private final S3Client s3;
    private final String bucket;

    public S3PhotoStorage(
            @Value("${audex.storage.bucket}") String bucket,
            @Value("${audex.storage.endpoint}") String endpoint,
            @Value("${audex.storage.region:auto}") String region,
            @Value("${audex.storage.access-key}") String accessKey,
            @Value("${audex.storage.secret-key}") String secretKey) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<StoredPhoto> get(String key) {
        try {
            var res = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredPhoto(res.asByteArray(), res.response().contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }
}
