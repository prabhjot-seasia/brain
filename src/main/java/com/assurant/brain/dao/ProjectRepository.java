package com.assurant.brain.dao;

import com.assurant.brain.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByLanguage(String language);

    boolean existsById(String id);
}
