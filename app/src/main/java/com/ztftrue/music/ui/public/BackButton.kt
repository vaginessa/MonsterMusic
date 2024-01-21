package com.ztftrue.music.ui.public

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ztftrue.music.Router

@Composable
fun BackButton(
    navController: NavHostController
) {
    fun NavHostController.navigateBack(onIsLastComposable: () -> Unit = {}) {
        if (Router.MainView.route == currentDestination?.navigatorName) {
            onIsLastComposable()
        } else {
            navigateUp()
        }
    }
    IconButton(onClick = {
        navController.navigateBack { }
    }) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
    }
}


