package com.artacademy.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Bài học. Không bao giờ xóa, chỉ ẩn (is_hidden) để không làm hỏng tiến độ của người đã học. */
@Entity
@Table(name = "course_lessons")
public class CourseLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    private CourseModule module;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

    /** "/uploads/xxx.mp4" hoặc link ngoài. Chỉ API nội bộ (cho C) và admin mới thấy; API public ẩn đi. */
    @Column(name = "video_url", length = 500)
    private String videoUrl;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    /** Tài liệu PDF đính kèm. */
    @Column(name = "material_url", length = 500)
    private String materialUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Bài học thử miễn phí: ai cũng xem được video. */
    @Column(name = "is_preview", nullable = false)
    private boolean preview;

    /** Bài bắt buộc: C dùng để xét hoàn thành khóa. */
    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "is_hidden", nullable = false)
    private boolean hidden;

    protected CourseLesson() {
    }

    public CourseLesson(CourseModule module, String title, int sortOrder) {
        this.module = module;
        this.title = title;
        this.sortOrder = sortOrder;
    }

    /** Bài chỉ thực sự hiển thị khi bản thân bài và module cha đều không bị ẩn. */
    public boolean isVisible() {
        return !hidden && !module.isHidden();
    }

    public Long getId() {
        return id;
    }

    public CourseModule getModule() {
        return module;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getMaterialUrl() {
        return materialUrl;
    }

    public void setMaterialUrl(String materialUrl) {
        this.materialUrl = materialUrl;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isPreview() {
        return preview;
    }

    public void setPreview(boolean preview) {
        this.preview = preview;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }
}
