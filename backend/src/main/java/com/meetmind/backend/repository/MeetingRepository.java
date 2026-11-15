package com.meetmind.backend.repository;

import com.meetmind.backend.entity.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long>
{
    List<Meeting> findByOwnerIdOrderByIdAsc(Long ownerId);

    // Looking a meeting up together with its owner means that someone else's
    // meeting simply "does not exist" for you.
    Optional<Meeting> findByIdAndOwnerId(Long id, Long ownerId);

    @Modifying
    @Query("update Meeting m set m.ownerId = :ownerId where m.ownerId is null")
    int claimUnowned(@Param("ownerId") Long ownerId);
}
