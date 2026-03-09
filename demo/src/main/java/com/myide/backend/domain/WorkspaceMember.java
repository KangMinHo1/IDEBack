package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"workspace_id", "user_id"})
        }
)
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", referencedColumnName = "uuid")
    private Workspace workspace;

    @Column(nullable = false)
    private String userId;
}