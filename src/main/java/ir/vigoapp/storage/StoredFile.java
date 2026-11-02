package ir.vigoapp.storage;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="files")
class StoredFile {
    @Id @Column(name="file_id") UUID id;
    @Column(name="coach_id",nullable=false) UUID coach;
    @Column(name="athlete_id",nullable=false) UUID athlete;
    @Column(nullable=false,length=20) String category;
    @Column(nullable=false,length=100) String title;
    @Column(length=500) String description;
    @Column(name="latest_version",nullable=false) int latestVersion;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
    @Column(name="deleted_at") Instant deletedAt;
    protected StoredFile() {}
    StoredFile(UUID coach,UUID athlete,String category,String title,String description) {
        id=UUID.randomUUID();this.coach=coach;this.athlete=athlete;this.category=category;this.title=title;this.description=description;
        latestVersion=1;createdAt=Instant.now();updatedAt=createdAt;
    }
}
