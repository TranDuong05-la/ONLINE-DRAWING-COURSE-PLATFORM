package com.artacademy.bundle;

import com.artacademy.bundle.dto.BundleDto;
import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Combo phía học viên: chỉ combo đang bật và mọi khóa đều PUBLISHED. */
@Service
@Transactional(readOnly = true)
public class BundlePublicService {

    private final BundleRepository bundleRepository;
    private final BundleMapper mapper;

    public BundlePublicService(BundleRepository bundleRepository, BundleMapper mapper) {
        this.bundleRepository = bundleRepository;
        this.mapper = mapper;
    }

    public List<BundleDto> list() {
        List<CourseBundle> bundles = bundleRepository.findByActiveTrueOrderByIdDesc();
        Map<Long, List<Course>> courses = mapper.coursesByBundle(bundles.stream().map(CourseBundle::getId).toList());
        return bundles.stream()
                .filter(b -> mapper.isVisible(b, courses.getOrDefault(b.getId(), List.of())))
                .map(b -> mapper.toPublic(b, courses.get(b.getId())))
                .toList();
    }

    public BundleDto get(Long id) {
        CourseBundle bundle = bundleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy combo"));
        List<Course> courses = mapper.coursesByBundle(List.of(id)).getOrDefault(id, List.of());
        if (!mapper.isVisible(bundle, courses)) {
            throw new NotFoundException("Không tìm thấy combo");
        }
        return mapper.toPublic(bundle, courses);
    }
}
