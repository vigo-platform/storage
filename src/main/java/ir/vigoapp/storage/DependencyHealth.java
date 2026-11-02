package ir.vigoapp.storage;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;

@Configuration
class DependencyHealth {
    @Bean HealthIndicator kafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers}")String servers) {
        return ()->{
            try(var admin=AdminClient.create(Map.of("bootstrap.servers",servers,"request.timeout.ms",3000,"default.api.timeout.ms",4000))){
                admin.describeCluster().clusterId().get(4,TimeUnit.SECONDS);return Health.up().build();
            }catch(Exception e){return Health.down().build();}
        };
    }
    @Bean HealthIndicator storageHealthIndicator(@Value("${storage.root}")String configured) {
        return ()->{Path root=Path.of(configured);try{
            FileSystemBlobStore.checkAncestors(root.toAbsolutePath());
            return Files.isDirectory(root)&&Files.isWritable(root)&&Files.isDirectory(root.resolve("objects"))?Health.up().build():Health.down().build();
        }catch(Exception e){return Health.down().build();}};
    }
}
