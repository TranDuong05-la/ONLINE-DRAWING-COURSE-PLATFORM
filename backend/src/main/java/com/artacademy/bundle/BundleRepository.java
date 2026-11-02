package com.artacademy.bundle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BundleRepository extends JpaRepository<CourseBundle, Long> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    List<CourseBundle> findAllByOrderByIdDesc();

    List<CourseBundle> findByActiveTrueOrderByIdDesc();
}
