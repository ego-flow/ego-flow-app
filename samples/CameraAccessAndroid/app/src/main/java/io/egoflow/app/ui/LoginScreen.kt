package io.egoflow.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.scale
import io.egoflow.app.R
import io.egoflow.app.settings.AuthPrefs

@Composable
fun LoginScreen(
    onLogin: (baseUrl: String, userId: String, password: String, rememberMe: Boolean) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var serverAddress by remember { mutableStateOf(AuthPrefs.egoFlowApiBaseUrl) }
    var userId by remember { mutableStateOf(AuthPrefs.egoFlowUserId) }
    var password by remember { mutableStateOf(AuthPrefs.egoFlowPassword) }
    var rememberMe by remember { mutableStateOf(AuthPrefs.rememberMe) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .padding(32.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Logo
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White,
                        ),
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.mipmap.ic_launcher_foreground),
                                contentDescription = "EgoFlow Logo",
                                modifier = Modifier.size(56.dp),
                                tint = Color.Unspecified,
                            )
                        }
                    }

                    // Title + Subtitle
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Log in",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Welcome to EgoFlow",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Input Fields
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Server Address
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Server Address",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            OutlinedTextField(
                                value = serverAddress,
                                onValueChange = { serverAddress = it },
                                placeholder = { Text("http://server-url") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                            )
                        }

                        // User ID
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "ID",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            OutlinedTextField(
                                value = userId,
                                onValueChange = { userId = it },
                                placeholder = { Text("your-id") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                            )
                        }

                        // Password
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Password",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = { Text("password") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }

                    // Remember Me Checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = { rememberMe = it },
                            modifier = Modifier.scale(0.9f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Remember me",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Login Button
                    Button(
                        onClick = {
                            onLogin(
                                serverAddress.trim(),
                                userId.trim(),
                                password.trim(),
                                rememberMe,
                            )
                        },
                        enabled = !isLoading && serverAddress.isNotBlank() && userId.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        ),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                text = "Log in",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }

                    // Footer Text
                    Text(
                        text = "Don't have an account?\nRequest your Server Admin",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
