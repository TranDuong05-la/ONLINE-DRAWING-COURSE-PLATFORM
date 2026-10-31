package com.artacademy.bundle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BundleRepository extends JpaRepository<CourseBundle, Integer> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Integer id);

    List<CourseBundle> findAllByOrderByIdDesc();

    List<CourseBundle> findByActiveTrueOrderByIdDesc();
}
