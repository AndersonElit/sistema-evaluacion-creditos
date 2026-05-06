package com.msnotifications;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class BeanConfig {
    // Wire domain use cases with infrastructure adapters here using @Produces/@Inject
    // Example:
    // @Inject SomePort somePort;
    // @Produces @ApplicationScoped
    // public SomeUseCase someUseCase() { return new SomeUseCase(somePort); }
}
