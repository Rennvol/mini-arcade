package com.rennvol.miniarcade.games.game2048

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.rennvol.miniarcade.data.dataStore
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.launch
import kotlin.math.abs

// Puzzle skill: board model separate from visuals, resolve/undo
private fun emptyBoard(): Array<IntArray> = Array(4){ IntArray(4){0} }

private fun addRandom(b: Array<IntArray>){
    val empties = mutableListOf<Pair<Int,Int>>()
    for(r in 0..3) for(c in 0..3) if(b[r][c]==0) empties.add(r to c)
    if(empties.isEmpty()) return
    val (r,c) = empties.random()
    b[r][c] = if(Math.random()<0.9) 2 else 4
}

private fun copyBoard(b: Array<IntArray>): Array<IntArray> = Array(4){ b[it].copyOf() }
private fun boardsEqual(a: Array<IntArray>, b: Array<IntArray>): Boolean { for(r in 0..3) for(c in 0..3) if(a[r][c]!=b[r][c]) return false; return true }

private enum class Dir { Left, Right, Up, Down }

private fun slideAndMerge(b: Array<IntArray>, dir: Dir): Pair<Array<IntArray>, Int> {
    val nb = copyBoard(b)
    var gained = 0
    fun processLine(line: IntArray): IntArray {
        val filtered = line.filter{it!=0}.toMutableList()
        var g=0
        var i=0
        while(i < filtered.size-1){
            if(filtered[i]==filtered[i+1]){ filtered[i]*=2; g+=filtered[i]; filtered.removeAt(i+1) }
            i++
        }
        while(filtered.size<4) filtered.add(0)
        gained+=g
        return filtered.toIntArray()
    }
    when(dir){
        Dir.Left -> for(r in 0..3) nb[r]=processLine(nb[r])
        Dir.Right -> for(r in 0..3){ val rev = nb[r].reversed().toIntArray(); val p = processLine(rev); nb[r]=p.reversed().toIntArray() }
        Dir.Up -> for(c in 0..3){ val col = IntArray(4){ nb[it][c] }; val p=processLine(col); for(r in 0..3) nb[r][c]=p[r] }
        Dir.Down -> for(c in 0..3){ val col = IntArray(4){ nb[it][c] }.reversed().toIntArray(); val p=processLine(col); val rev=p.reversed().toIntArray(); for(r in 0..3) nb[r][c]=rev[r] }
    }
    return nb to gained
}

private fun canMove(b: Array<IntArray>): Boolean {
    for(r in 0..3) for(c in 0..3) if(b[r][c]==0) return true
    for(r in 0..3) for(c in 0..3){ val v=b[r][c]; if(r<3 && b[r+1][c]==v) return true; if(c<3 && b[r][c+1]==v) return true }
    return false
}

private fun tileColor(v: Int): Color = when(v){
    2-> Color(0xFFEEE4DA); 4-> Color(0xFFEDE0C8); 8-> Color(0xFFF2B179); 16-> Color(0xFFF59563)
    32-> Color(0xFFF67C5F); 64-> Color(0xFFF65E3B); 128-> Color(0xFFEDCF72); 256-> Color(0xFFEDCC61)
    512-> Color(0xFFEDC850); 1024-> Color(0xFFEDC53F); 2048-> Color(0xFFEDC22E); else-> ArcadeTokens.Primary
}
private fun tileTextColor(v:Int): Color = if(v<=4) ArcadeTokens.Text else Color.White

@Composable
fun Game2048Screen(onBack: ()->Unit){
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf(emptyBoard().also{ addRandom(it); addRandom(it) }) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(0) }
    var won by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf(listOf<Pair<Array<IntArray>,Int>>()) }

    LaunchedEffect(Unit){ ctx.dataStore.data.collect{ best = it[intPreferencesKey("s2048_best")]?:0 } }
    suspend fun saveBest(v:Int){ ctx.dataStore.edit{ if(v>(it[intPreferencesKey("s2048_best")]?:0)) it[intPreferencesKey("s2048_best")]=v } }

    fun doMove(dir: Dir){
        if(over) return
        val (nb, gain) = slideAndMerge(board, dir)
        if(boardsEqual(board, nb)) return
        undoStack = (undoStack + (copyBoard(board) to score)).takeLast(20)
        addRandom(nb)
        board=nb; score+=gain
        if(score>best){ best=score; scope.launch{ saveBest(score) } }
        if(board.any{ row-> row.any{it==2048} }) won=true
        if(!canMove(board)) over=true
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("2048", style=MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    IconButton(onClick={ if(undoStack.isNotEmpty()){ val (b,s)=undoStack.last(); board=b; score=s; undoStack=undoStack.dropLast(1); over=false } }, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.Undo,"undo") }
                    IconButton(onClick={ board=emptyBoard().also{ addRandom(it); addRandom(it) }; score=0; won=false; over=false; undoStack=listOf() }, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.Refresh,"reset") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                ScoreBox2048("Score", score, Modifier.weight(1f))
                ScoreBox2048("Best", best, Modifier.weight(1f))
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFBBADA0)).padding(8.dp)
                .pointerInput(Unit){
                    var sx=0f; var sy=0f
                    detectDragGestures(onDragStart={ sx=it.x; sy=it.y }, onDragEnd={
                        val dx=sx - 0f // unused
                    }, onDrag={_, _->{}} )
                }
            ){
                // swipe via drag end detection: use raw pointerInput with await
            }
            // actual swipe board with drag detection
            var dragX by remember { mutableStateOf(0f) }
            var dragY by remember { mutableStateOf(0f) }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFBBADA0)).padding(8.dp)
                    .pointerInput(Unit){
                        awaitPointerEventScope{
                            while(true){
                                val down = awaitPointerEvent().changes.firstOrNull() ?: continue
                                if(!down.pressed) continue
                                val start = down.position
                                // wait for up
                                var end = start
                                while(true){
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull() ?: break
                                    end = c.position
                                    if(!c.pressed) break
                                    c.consume()
                                }
                                val dx = end.x - start.x
                                val dy = end.y - start.y
                                if(abs(dx)<30 && abs(dy)<30) continue
                                val dir = if(abs(dx)>abs(dy)) { if(dx>0) Dir.Right else Dir.Left } else { if(dy>0) Dir.Down else Dir.Up }
                                doMove(dir)
                            }
                        }
                    }
            ){
                Column(Modifier.fillMaxSize(), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    for(r in 0..3){
                        Row(Modifier.weight(1f), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            for(c in 0..3){
                                val v = board[r][c]
                                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(if(v==0) Color(0xFFCDC1B4) else tileColor(v)), contentAlignment=Alignment.Center){
                                    if(v!=0) Text("$v", color=tileTextColor(v), fontWeight=FontWeight.Black, fontSize= when{
                                        v<100-> 22.sp; v<1000-> 18.sp; else-> 14.sp
                                    })
                                }
                            }
                        }
                    }
                }
                if(won && !over){
                    Box(Modifier.fillMaxSize().background(Color(0xAAFFFFFF)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("You reached 2048!", style=MaterialTheme.typography.titleLarge)
                            Button(onClick={ won=false }, modifier=Modifier.height(44.dp)){ Text("Continue") }
                        }
                    }
                }
                if(over){
                    Box(Modifier.fillMaxSize().background(Color(0xAA1A1A2E)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("Game Over", style=MaterialTheme.typography.displayMedium, color=Color.White)
                            Button(onClick={ board=emptyBoard().also{ addRandom(it); addRandom(it) }; score=0; won=false; over=false }, modifier=Modifier.height(44.dp)){ Text("Restart") }
                        }
                    }
                }
            }
            // arrow buttons as alternative to swipe (accessibility + AA)
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically){
                Ctrl2048("↑", Modifier.weight(1f)){ doMove(Dir.Up) }
                Ctrl2048("↓", Modifier.weight(1f)){ doMove(Dir.Down) }
                Ctrl2048("←", Modifier.weight(1f)){ doMove(Dir.Left) }
                Ctrl2048("→", Modifier.weight(1f)){ doMove(Dir.Right) }
            }
            Text("Swipe on board or use arrows • Undo keeps last 20 moves", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
        }
    }
}
@Composable private fun ScoreBox2048(label:String, v:Int, mod:Modifier){
    Column(mod.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), horizontalAlignment=Alignment.CenterHorizontally){
        Text(label, style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint); Text("$v", style=MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun Ctrl2048(txt:String, mod:Modifier, onClick:()->Unit){
    Button(onClick=onClick, modifier=mod.height(48.dp), shape=RoundedCornerShape(14.dp), colors=ButtonDefaults.buttonColors(containerColor=ArcadeTokens.Surface, contentColor=ArcadeTokens.Text)){ Text(txt, style=MaterialTheme.typography.titleLarge) }
}
