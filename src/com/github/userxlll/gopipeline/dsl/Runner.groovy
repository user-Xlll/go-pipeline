package com.github.userxlll.gopipeline.dsl

class Runner {
    static Object run(Object obj, Closure<?> closure) {
        if (closure != null) {
            closure.setDelegate(obj)
            closure.setResolveStrategy(Closure.DELEGATE_ONLY)
            try {
                closure()
         } catch (groovy.lang.MissingPropertyException ex) {
                throw new Exception("Option ${ex.property} used as property, add parentheses ()", ex)
        }
        }
        return obj
    }
}
