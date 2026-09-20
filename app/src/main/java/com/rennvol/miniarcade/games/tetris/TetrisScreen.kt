package com.rennvol.miniarcade.games.tetris

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import com.rennvol.miniarcade.data.dataStore
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

private const val W = 10
private const val H = 20
private data class Piece(val shape: List<Pair<Int,Int>>, val color: Color)
private val PIECES = listOf(
    Piece(listOf(0 to 0,1 to 0,2 to 0,3 to 0), ArcadeTokens.TetrisI),
    Piece(listOf(0 to 0,0 to 1,1 to 1,2 to 1), ArcadeTokens.TetrisJ),
    Piece(listOf(2 to 0,0 to 1,1 to 1,2 to 1), ArcadeTokens.TetrisL),
    Piece(listOf(0 to 0,1 to 0,0 to 1,1 to 1), ArcadeTokens.TetrisO),
    Piece(listOf(1 to 0,2 to 0,0 to 1,1 to 1), ArcadeTokens.TetrisS),
    Piece(listOf(1 to 0,0 to 1,1 to 1,2 to 1), ArcadeTokens.TetrisT),
    Piece(listOf(0 to 0,1 to 0,1 to 1,2 to 1), ArcadeTokens.TetrisZ),
)
private fun rotate(s: List<Pair<Int,Int>>): List<Pair<Int,Int>> = s.map { (x,y) -> y to -x }

@Composable
fun TetrisScreen(onBack: ()->Unit) {
    val ctx = LocalContext.current
    var board by remember { mutableStateOf(Array(H){ arrayOfNulls<Color>(W) }) }
    var cur by remember { mutableStateOf(PIECES.random()) }
    var next by remember { mutableStateOf(PIECES.random()) }
    var px by remember { mutableIntStateOf(3) }
    var py by remember { mutableIntStateOf(0) }
    var rot by remember { mutableStateOf(cur.shape) }
    var score by remember { mutableIntStateOf(0) }
    var hi by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var tickMs by remember { mutableIntStateOf(520) }

    LaunchedEffect(Unit){
        ctx.dataStore.data.map{it[intPreferencesKey("tetris_hi")]?:0}.collect{ hi = it }
    }
    suspend fun saveHi(v:Int){ ctx.dataStore.edit{ if(v>(it[intPreferencesKey("tetris_hi")]?:0)) it[intPreferencesKey("tetris_hi")]=v } }

    fun canPlace(shape: List<Pair<Int,Int>>, x:Int,y:Int): Boolean {
        for((dx,dy) in shape){
            val nx=x+dx; val ny=y+dy
            if(nx !in 0 until W || ny !in 0 until H) return false
            if(board[ny][nx]!=null) return false
        }
        return true
    }
    fun ghostY(): Int { var gy=py; while(canPlace(rot, px, gy+1)) gy++; return gy }
    fun spawn(){
        cur=next; next=PIECES.random(); rot=cur.shape; px=3; py=0
        if(!canPlace(rot,px,py)) over=true
    }
    fun lock(){
        val nb = Array(H){ r-> board[r].copyOf() }
        for((dx,dy) in rot){
            val nx=px+dx; val ny=py+dy
            if(ny in 0 until H && nx in 0 until W) nb[ny][nx]=cur.color
        }
        var cleared=0
        val remaining = nb.filter { row -> row.any{it==null}.also{ if(!it) cleared++ } }
        val newBoard = Array(H){ arrayOfNulls<Color>(W) }
        val offset = H - remaining.size
        for(i in remaining.indices) newBoard[offset+i]=remaining[i]
        board=newBoard
        if(cleared>0){
            score += when(cleared){1->100;2->300;3->500;4->800;else->0}
            tickMs = (520 - score/50).coerceAtLeast(120)
            if(score>hi) hi=score
        }
        spawn()
    }
    fun move(dx:Int){ if(paused||over) return; if(canPlace(rot, px+dx, py)) px+=dx }
    fun rotateCur(){
        if(paused||over) return
        val nr = rotate(rot)
        for(kick in listOf(0,1,-1,2,-2)){ if(canPlace(nr, px+kick, py)){ rot=nr; px+=kick; return } }
    }
    fun drop(){ if(paused||over) return; py=ghostY(); lock() }
    fun softDrop(){ if(paused||over) return; if(canPlace(rot,px,py+1)) py++ else lock() }

    LaunchedEffect(paused, over, tickMs){
        while(!paused && !over){ delay(tickMs.toLong()); if(canPlace(rot, px, py+1)) py++ else lock() }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                SmallBtn(Icons.Filled.ArrowBack, "back", onBack)
                Text("TETRIS", style=MaterialTheme.typography.titleLarge)
                SmallBtn(if(paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if(paused)"resume" else "pause", { if(!over) paused = !paused })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                ScoreBox("Score", score, Modifier.weight(1f))
                ScoreBox("Best", hi, Modifier.weight(1f))
                NextBox(next, Modifier.weight(1f))
            }
            // board: weight 1f + BoxWithConstraints ensures 20th row visible, never under control bar
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Text).padding(8.dp), contentAlignment=Alignment.Center){
                val cellByW = maxWidth / W
                val cellByH = maxHeight / H
                val cell = minOf(cellByW, cellByH)
                val bw = cell * W
                val bh = cell * H
                Box(Modifier.size(bw, bh), contentAlignment=Alignment.Center){
                    val gy = ghostY()
                    Canvas(Modifier.fillMaxSize()){
                        val cellW = size.width / W; val cellH = size.height / H
                        for(r in 0 until H) for(c in 0 until W){
                            val col = board[r][c]
                            if(col!=null) drawRect(col, Offset(c*cellW, r*cellH), Size(cellW-1, cellH-1))
                            else drawRect(Color(0x1AFFFFFF), Offset(c*cellW, r*cellH), Size(cellW-1, cellH-1))
                        }
                        for((dx,dy) in rot){
                            val gx=px+dx; val gyy=gy+dy
                            if(gyy in 0 until H && gx in 0 until W && gy!=py) drawRect(ArcadeTokens.Ghost, Offset(gx*cellW, gyy*cellH), Size(cellW-1, cellH-1))
                        }
                        for((dx,dy) in rot){
                            val x=px+dx; val y=py+dy
                            if(y in 0 until H && x in 0 until W){
                                drawRect(cur.color, Offset(x*cellW, y*cellH), Size(cellW-1, cellH-1))
                                drawRect(Color.White.copy(alpha=0.35f), Offset(x*cellW, y*cellH), Size(cellW-1, 3f))
                            }
                        }
                    }
                    if(over){
                        Box(Modifier.fillMaxSize().background(Color(0xAA1A1A2E)), contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text("Game Over", style=MaterialTheme.typography.displayMedium, color=Color.White)
                                Text("Score "+score, color=Color.White)
                                Button(onClick={ board=Array(H){ arrayOfNulls(W) }; score=0; over=false; paused=false; tickMs=520; cur=PIECES.random(); next=PIECES.random(); rot=cur.shape; px=3; py=0 }, modifier=Modifier.height(44.dp)){ Text("Restart") }
                            }
                        }
                    } else if(paused){
                        Box(Modifier.fillMaxSize().background(Color(0xAA1A1A2E)), contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){
                                Text("Paused", style=MaterialTheme.typography.displayMedium, color=Color.White)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick={paused=false}, modifier=Modifier.height(44.dp)){ Text("Resume") }
                            }
                        }
                    }
                }
            }
            // fixed 56dp control bar with navigationBarsPadding — never overlaps board
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically){
                CtrlBtn("◀", Modifier.weight(1f)){ move(-1) }
                CtrlBtn("▼", Modifier.weight(1f)){ softDrop() }
                CtrlBtn("▶", Modifier.weight(1f)){ move(1) }
                CtrlBtn("↻", Modifier.weight(1f)){ rotateCur() }
                CtrlBtn("⤓", Modifier.weight(1f), tint=ArcadeTokens.Primary){ drop() }
            }
            LaunchedEffect(score){ if(score>0) saveHi(score) }
        }
    }
}
@Composable private fun SmallBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc:String, onClick:()->Unit){
    Surface(shape=RoundedCornerShape(12.dp), color=ArcadeTokens.Surface, shadowElevation=1.dp, modifier=Modifier.size(44.dp).clickable(onClick=onClick)){
        Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center){ Icon(icon, desc, tint=ArcadeTokens.Text) }
    }
}
@Composable private fun ScoreBox(label:String, v:Int, mod:Modifier){
    Column(mod.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), horizontalAlignment=Alignment.CenterHorizontally){
        Text(label, style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint); Text("$v", style=MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun NextBox(n: Piece, mod:Modifier){
    Column(mod.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), horizontalAlignment=Alignment.CenterHorizontally){
        Text("Next", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
        Canvas(Modifier.size(44.dp)){
            val s=n.shape; val minX=s.minOf{it.first}; val maxX=s.maxOf{it.first}; val minY=s.minOf{it.second}; val maxY=s.maxOf{it.second}
            val gw=maxX-minX+1; val gh=maxY-minY+1; val cell = minOf(size.width/gw, size.height/gh) *0.9f
            val ox=(size.width - gw*cell)/2; val oy=(size.height - gh*cell)/2
            for((x,y) in s) drawRect(n.color, Offset(ox+(x-minX)*cell, oy+(y-minY)*cell), Size(cell-2, cell-2))
        }
    }
}
@Composable private fun CtrlBtn(txt:String, mod:Modifier, tint:Color=ArcadeTokens.Text, onClick:()->Unit){
    Button(onClick=onClick, modifier=mod.height(48.dp), shape=RoundedCornerShape(14.dp), colors=ButtonDefaults.buttonColors(containerColor=ArcadeTokens.Surface, contentColor=tint), elevation=ButtonDefaults.buttonElevation(1.dp)){
        Text(txt, style=MaterialTheme.typography.titleLarge)
    }
}
