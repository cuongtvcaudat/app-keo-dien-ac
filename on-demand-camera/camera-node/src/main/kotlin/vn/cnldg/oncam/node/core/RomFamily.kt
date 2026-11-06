package vn.cnldg.oncam.node.core

/** Họ giao diện Android của hãng máy — mỗi họ có cách chặn app chạy nền và tên mục cài đặt khác nhau. */
enum class RomFamily {
    /** Samsung One UI (máy camera của dự án: Galaxy A54). */
    SAMSUNG,

    /** Oppo / Realme / OnePlus (ColorOS, realme UI, OxygenOS 13+ cùng gốc ColorOS). */
    COLOR_OS,

    /** Hãng khác: hướng dẫn chung. */
    OTHER,
    ;

    companion object {
        /** Nhận diện từ `Build.MANUFACTURER`. */
        fun of(manufacturer: String): RomFamily = when (manufacturer.trim().lowercase()) {
            "samsung" -> SAMSUNG
            "oppo", "realme", "oneplus" -> COLOR_OS
            else -> OTHER
        }
    }
}
