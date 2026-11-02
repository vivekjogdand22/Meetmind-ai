package com.meetmind.backend.repository;

import com.meetmind.backend.entity.PlanTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanTaskRepository extends JpaRepository<PlanTask, Long>
{
    List<PlanTask> findByMeetingIdOrderBySortOrderAscIdAsc(Long meetingId);

    boolean existsByMeetingId(Long meetingId);

    void deleteByMeetingId(Long meetingId);
}
