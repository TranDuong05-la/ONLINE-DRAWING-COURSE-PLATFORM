package com.artacademy.bundle;

/** Quy tắc cố định của combo (dùng chung cho kiểm tra ở API và ở service). */
public final class BundleRules {

    /** Số khóa học khác nhau tối thiểu / tối đa trong một combo. */
    public static final int MIN_COURSES = 2;
    public static final int MAX_COURSES = 10;

    public static final String COURSE_COUNT_MESSAGE =
            "Combo cần từ " + MIN_COURSES + " đến " + MAX_COURSES + " khóa học";

    private BundleRules() {
    }
}
