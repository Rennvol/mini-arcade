package com.rennvol.miniarcade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rennvol.miniarcade.games.blackjack.BlackjackScreen
import com.rennvol.miniarcade.games.flappy.FlappyBirdScreen
import com.rennvol.miniarcade.games.game2048.Game2048Screen
import com.rennvol.miniarcade.games.minesweeper.MinesweeperScreen
import com.rennvol.miniarcade.games.poker.PokerScreen
import com.rennvol.miniarcade.games.paintball.PaintballScreen
import com.rennvol.miniarcade.games.shooter.ShooterScreen
import com.rennvol.miniarcade.games.solitaire.SolitaireScreen
import com.rennvol.miniarcade.games.tetris.TetrisScreen
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import com.rennvol.miniarcade.ui.theme.MiniArcadeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiniArcadeTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(
                        onTetris = { nav.navigate("tetris") },
                        onSolitaire = { nav.navigate("solitaire") },
                        on2048 = { nav.navigate("2048") },
                        onMinesweeper = { nav.navigate("minesweeper") },
                        onPoker = { nav.navigate("poker") },
                        onBlackjack = { nav.navigate("blackjack") },
                        onFlappy = { nav.navigate("flappy") },
                        onPaintball = { nav.navigate("paintball") },
                        onShooter = { nav.navigate("shooter") }
                    ) }
                    composable("tetris") { TetrisScreen(onBack = { nav.popBackStack() }) }
                    composable("solitaire") { SolitaireScreen(onBack = { nav.popBackStack() }) }
                    composable("2048") { Game2048Screen(onBack = { nav.popBackStack() }) }
                    composable("minesweeper") { MinesweeperScreen(onBack = { nav.popBackStack() }) }
                    composable("poker") { PokerScreen(onBack = { nav.popBackStack() }) }
                    composable("blackjack") { BlackjackScreen(onBack = { nav.popBackStack() }) }
                    composable("flappy") { FlappyBirdScreen(onBack = { nav.popBackStack() }) }
                    composable("paintball") { PaintballScreen(onBack = { nav.popBackStack() }) }
                    composable("shooter") { ShooterScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

data class GameCard(val title: String, val desc: String, val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color, val route: String)

@Composable
fun HomeScreen(onTetris: ()->Unit, onSolitaire: ()->Unit, on2048: ()->Unit, onMinesweeper: ()->Unit, onPoker: ()->Unit, onBlackjack: ()->Unit, onFlappy: ()->Unit, onPaintball: ()->Unit, onShooter: ()->Unit) {
    val games = listOf(
        GameCard("Tetris","10x20 stack & clear", Icons.Filled.ViewModule, ArcadeTokens.Primary, "tetris"),
        GameCard("Solitaire","Klondike classic", Icons.Filled.Style, ArcadeTokens.Accent, "solitaire"),
        GameCard("2048","Swipe to merge", Icons.Filled.GridOn, ArcadeTokens.Secondary, "2048"),
        GameCard("Minesweeper","Find all mines", Icons.Filled.Flag, ArcadeTokens.Danger, "minesweeper"),
        GameCard("Poker","5-card draw vs CPU", Icons.Filled.Casino, ArcadeTokens.PrimaryDark, "poker"),
        GameCard("Blackjack","21 vs dealer", Icons.Filled.Style, ArcadeTokens.TetrisZ, "blackjack"),
        GameCard("Flappy Bird","Tap to earn +5 chips", Icons.Filled.Flight, ArcadeTokens.Secondary, "flappy"),
        GameCard("Paintball","Tap to shoot • score×3", Icons.Filled.SportsEsports, ArcadeTokens.Danger, "paintball"),
        GameCard("Shooter","Plane shooter • upgrades", Icons.Filled.FlightTakeoff, ArcadeTokens.PrimaryDark, "shooter"),
    )
    val actions = listOf(onTetris, onSolitaire, on2048, onMinesweeper, onPoker, onBlackjack, onFlappy, onPaintball, onShooter)
    Surface(color = ArcadeTokens.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ArcadeTokens.Surface).padding(20.dp)) {
                Text("MINI ARCADE", style = MaterialTheme.typography.labelSmall, color = ArcadeTokens.TextFaint)
                Spacer(Modifier.height(4.dp))
                Text("Offline. No ads. One APK.", style = MaterialTheme.typography.displayMedium, color = ArcadeTokens.Text)
                Spacer(Modifier.height(6.dp))
                Text("Tap a game to play. Scores saved on device.", style = MaterialTheme.typography.bodyMedium)
            }
            // chunk 2 per row to handle odd count
            for(chunk in games.chunked(2)){
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for((idx, g) in chunk.withIndex()){
                        val globalIdx = games.indexOf(g)
                        GameBentoCard(g, modifier = Modifier.weight(1f), onClick = actions[globalIdx])
                    }
                    if(chunk.size==1) Spacer(Modifier.weight(1f))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.BgMuted).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.WifiOff, contentDescription = null, tint = ArcadeTokens.TextMuted)
                Text("No internet permission — 100% offline", style = MaterialTheme.typography.labelLarge, color = ArcadeTokens.TextMuted)
            }
            Text("© Rennvol • v1.0.0", style = MaterialTheme.typography.labelSmall, color = ArcadeTokens.TextFaint, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GameBentoCard(card: GameCard, modifier: Modifier, onClick: ()->Unit) {
    Surface(
        modifier = modifier.height(148.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp), color = ArcadeTokens.Surface, shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(card.tint.copy(alpha = 0.15f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(card.icon, contentDescription = null, tint = card.tint, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(card.title, style = MaterialTheme.typography.titleLarge, color = ArcadeTokens.Text)
                Text(card.desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
