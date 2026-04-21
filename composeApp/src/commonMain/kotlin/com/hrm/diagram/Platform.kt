package com.hrm.diagram

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform