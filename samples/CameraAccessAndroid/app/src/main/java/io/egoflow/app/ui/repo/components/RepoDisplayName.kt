package io.egoflow.app.ui.repo.components

import io.egoflow.app.egoflow.RegisteredRepository

internal val RegisteredRepository.displayName: String
    get() = "$ownerId/$name"
