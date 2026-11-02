package ir.vigoapp.storage;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface FileRepository extends JpaRepository<StoredFile,UUID> {
    String SCOPE="f.deletedAt is null and (:admin=true or (:coach is not null and f.coach=:coach) or (:athlete is not null and f.athlete=:athlete))";
    @Query("select f from StoredFile f where f.id=:id and "+SCOPE)
    Optional<StoredFile> visible(@Param("id")UUID id,@Param("admin")boolean admin,@Param("coach")UUID coach,@Param("athlete")UUID athlete);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from StoredFile f where f.id=:id and "+SCOPE)
    Optional<StoredFile> locked(@Param("id")UUID id,@Param("admin")boolean admin,@Param("coach")UUID coach,@Param("athlete")UUID athlete);
    boolean existsByIdAndDeletedAtIsNull(UUID id);
    @Query("select f from StoredFile f where "+SCOPE+" and (:coachFilter is null or f.coach=:coachFilter) and (:athleteFilter is null or f.athlete=:athleteFilter) and (:category is null or f.category=:category)")
    Page<StoredFile> scoped(@Param("admin")boolean admin,@Param("coach")UUID coach,@Param("athlete")UUID athlete,
        @Param("coachFilter")UUID coachFilter,@Param("athleteFilter")UUID athleteFilter,@Param("category")String category,Pageable page);
}
