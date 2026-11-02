package com.meetmind.backend.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.LinkedHashSet;
import java.util.Set;


// ============================================================
// ONE TASK OF A MEETING'S PERT / CPM PLAN
//
// Only the inputs are stored here. The schedule (expected time,
// earliest start, slack, critical path ...) is calculated from
// them every time the plan is read, so it can never be stale.
// ============================================================

@Entity
@Table(name = "plan_task")
public class PlanTask
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ON DELETE CASCADE: when a meeting is deleted, the database
    // removes its plan as well.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Meeting meeting;

    @Column(nullable = false, length = 500)
    private String name;

    private String owner;

    // Copied from the AI action item for reference only.
    // They are not used by the schedule.
    private String priority;

    private String sourceDueDate;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    // Estimates in days. All three are set together,
    // or all three are empty (a draft task).
    private Double optimisticDays;

    private Double mostLikelyDays;

    private Double pessimisticDays;

    private int sortOrder;

    // Ids of the tasks that must finish before this one can start.
    // ON DELETE CASCADE removes these rows together with the task.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "plan_task_predecessor",
            joinColumns = @JoinColumn(name = "task_id")
    )
    @Column(name = "predecessor_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Set<Long> predecessorIds = new LinkedHashSet<>();


    public PlanTask()
    {
    }


    public boolean hasEstimates()
    {
        return optimisticDays != null
                && mostLikelyDays != null
                && pessimisticDays != null;
    }


    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }


    public Meeting getMeeting()
    {
        return meeting;
    }

    public void setMeeting(Meeting meeting)
    {
        this.meeting = meeting;
    }


    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }


    public String getOwner()
    {
        return owner;
    }

    public void setOwner(String owner)
    {
        this.owner = owner;
    }


    public String getPriority()
    {
        return priority;
    }

    public void setPriority(String priority)
    {
        this.priority = priority;
    }


    public String getSourceDueDate()
    {
        return sourceDueDate;
    }

    public void setSourceDueDate(String sourceDueDate)
    {
        this.sourceDueDate = sourceDueDate;
    }


    public String getEvidence()
    {
        return evidence;
    }

    public void setEvidence(String evidence)
    {
        this.evidence = evidence;
    }


    public Double getOptimisticDays()
    {
        return optimisticDays;
    }

    public void setOptimisticDays(Double optimisticDays)
    {
        this.optimisticDays = optimisticDays;
    }


    public Double getMostLikelyDays()
    {
        return mostLikelyDays;
    }

    public void setMostLikelyDays(Double mostLikelyDays)
    {
        this.mostLikelyDays = mostLikelyDays;
    }


    public Double getPessimisticDays()
    {
        return pessimisticDays;
    }

    public void setPessimisticDays(Double pessimisticDays)
    {
        this.pessimisticDays = pessimisticDays;
    }


    public int getSortOrder()
    {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder)
    {
        this.sortOrder = sortOrder;
    }


    public Set<Long> getPredecessorIds()
    {
        return predecessorIds;
    }

    public void setPredecessorIds(Set<Long> predecessorIds)
    {
        this.predecessorIds = predecessorIds;
    }
}
