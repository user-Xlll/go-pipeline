package com.github.userxlll.gopipeline.dsl

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

@Retention(RetentionPolicy.RUNTIME)
@interface Section {

    String value()

}
