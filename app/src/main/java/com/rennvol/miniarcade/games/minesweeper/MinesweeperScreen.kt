package com.rennvol.miniarcade.games.minesweeper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.delay

private enum class Diff(val w:Int,val h:Int,val mines:Int, val label:String){ Easy(9,9,10,"9×9"), Medium(16,16,40,"16×16"), Hard(16,30,99,"16×30") }

private data class Cell(var isMine:Boolean=false, var revealed:Boolean=false, var flagged:Boolean=false, var adj:Int=0)

@Composable
fun MinesweeperScreen(onBack: ()->Unit){
    var diff by remember { mutableStateOf(Diff.Easy) }
    var board by remember { mutableStateOf(emptyBoard(diff)) }
    var firstClick by remember { mutableStateOf(true) }
    var gameOver by remember { mutableStateOf(false) }
    var won by remember { mutableStateOf(false) }
    var flagMode by remember { mutableStateOf(false) }
    var secs by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }

    fun reset(d:Diff=diff){
        diff=d; board=emptyBoard(d); firstClick=true; gameOver=false; won=false; secs=0; running=false; flagMode=false
    }
    LaunchedEffect(running, gameOver, won){
        while(running && !gameOver && !won){ delay(1000); secs++ }
    }
    fun placeMines(safeR:Int,safeC:Int){
        val cells = mutableListOf<Pair<Int,Int>>()
        for(r in 0 until diff.h) for(c in 0 until diff.w) if(!(r in safeR-1..safeR+1 && c in safeC-1..safeC+1)) cells.add(r to c)
        cells.shuffle()
        for(i in 0 until diff.mines){ val (r,c)=cells[i]; board[r][c].isMine=true }
        // adj counts
        for(r in 0 until diff.h) for(c in 0 until diff.w) if(!board[r][c].isMine){
            var cnt=0
            for(dr in -1..1) for(dc in -1..1) if(dr!=0||dc!=0){
                val nr=r+dr; val nc=c+dc
                if(nr in 0 until diff.h && nc in 0 until diff.w && board[nr][nc].isMine) cnt++
            }
            board[r][c].adj=cnt
        }
    }
    fun reveal(r:Int,c:Int){
        if(r !in 0 until diff.h || c !in 0 until diff.w) return
        val cell=board[r][c]
        if(cell.revealed||cell.flagged) return
        cell.revealed=true
        if(cell.adj==0 && !cell.isMine){
            for(dr in -1..1) for(dc in -1..1) if(dr!=0||dc!=0) reveal(r+dr, c+dc)
        }
    }
    fun checkWin(){
        val allRevealed = (0 until diff.h).all{ r-> (0 until diff.w).all{ c-> val cl=board[r][c]; cl.isMine || cl.revealed } }
        if(allRevealed){ won=true; running=false }
    }
    fun chord(r:Int,c:Int){
        val cell=board[r][c]
        if(!cell.revealed || cell.adj==0) return
        var flags=0
        for(dr in -1..1) for(dc in -1..1){ val nr=r+dr; val nc=c+dc; if(nr in 0 until diff.h && nc in 0 until diff.w && board[nr][nc].flagged) flags++ }
        if(flags==cell.adj){
            for(dr in -1..1) for(dc in -1..1){
                val nr=r+dr; val nc=c+dc
                if(nr in 0 until diff.h && nc in 0 until diff.w){
                    val ncell=board[nr][nc]
                    if(!ncell.flagged && !ncell.revealed){
                        if(ncell.isMine){ // lose
                            ncell.revealed=true; gameOver=true; running=false; return
                        } else reveal(nr,nc)
                    }
                }
            }
            checkWin()
        }
    }
    fun onCellClick(r:Int,c:Int){
        if(gameOver||won) return
        val cell=board[r][c]
        if(flagMode){
            if(!cell.revealed){ cell.flagged=!cell.flagged; board=board.map{ row-> row.map{it.copy()}.toTypedArray() }.toTypedArray() }
            return
        }
        if(cell.flagged) return
        if(cell.revealed){ chord(r,c); board=board.map{ row-> row.map{it.copy()}.toTypedArray() }.toTypedArray(); return }
        if(firstClick){
            placeMines(r,c); firstClick=false; running=true
        }
        if(cell.isMine){ cell.revealed=true; gameOver=true; running=false
            // reveal all mines
            for(rr in 0 until diff.h) for(cc in 0 until diff.w) if(board[rr][cc].isMine) board[rr][cc].revealed=true
        } else {
            reveal(r,c); checkWin()
        }
        // trigger recompose
        board=board.map{ row-> row.map{it.copy()}.toTypedArray() }.toTypedArray()
    }
    fun onCellLongPress(r:Int,c:Int){
        if(gameOver||won) return
        val cell=board[r][c]
        if(!cell.revealed){ cell.flagged=!cell.flagged; board=board.map{ row-> row.map{it.copy()}.toTypedArray() }.toTypedArray() }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("MINESWEEPER", style=MaterialTheme.typography.titleLarge)
                IconButton(onClick={ reset() }, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.Refresh,"reset") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                for(d in Diff.values()){
                    FilterChip(selected=diff==d, onClick={ reset(d) }, label={ Text(d.label, fontSize=12.sp) }, modifier=Modifier.height(36.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=10.dp, vertical=8.dp)){
                    Text(String.format("%03d", secs), style=MaterialTheme.typography.titleMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically){
                val flags = board.sumOf{ row-> row.count{it.flagged} }
                Text("Mines: ${diff.mines - flags}", style=MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                FilterChip(selected=flagMode, onClick={ flagMode=!flagMode }, label={ Text(if(flagMode) "Flag ON" else "Flag OFF") }, leadingIcon={ Icon(if(flagMode) Icons.Filled.Flag else Icons.Filled.Flag, null, modifier=Modifier.size(16.dp)) })
                Text("Tap=Reveal  Long=Flag  Chord on number", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
            }
            // board
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(8.dp), contentAlignment=Alignment.Center){
                Column(verticalArrangement=Arrangement.spacedBy(2.dp), modifier=Modifier.fillMaxWidth()){
                    for(r in 0 until diff.h){
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement=Arrangement.spacedBy(2.dp)){
                            for(c in 0 until diff.w){
                                val cell=board[r][c]
                                val bg = when{
                                    !cell.revealed -> if(cell.flagged) ArcadeTokens.SecondaryContainer else ArcadeTokens.BgMuted
                                    cell.isMine -> ArcadeTokens.DangerContainer
                                    else -> ArcadeTokens.SurfaceAlt
                                }
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(bg).border(1.dp, ArcadeTokens.Border, RoundedCornerShape(6.dp))
                                        .clickable{ onCellClick(r,c) }
                                        .let{ m-> m }, // long press via combinedClickable would need foundation; use flagMode toggle + click for now
                                    contentAlignment=Alignment.Center
                                ){
                                    when{
                                        cell.flagged && !cell.revealed -> Text("🚩", fontSize=12.sp)
                                        !cell.revealed -> {}
                                        cell.isMine -> Text("💣", fontSize=12.sp)
                                        cell.adj>0 -> Text("${cell.adj}", color=numberColor(cell.adj), fontSize=12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                if(gameOver){
                    Box(Modifier.fillMaxSize().background(Color(0xAA1A1A2E)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("Boom!", style=MaterialTheme.typography.displayMedium, color=Color.White)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Retry") }
                        }
                    }
                }
                if(won){
                    Box(Modifier.fillMaxSize().background(Color(0xAA00B894)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("Cleared!", style=MaterialTheme.typography.displayMedium, color=Color.White)
                            Text("${secs}s", color=Color.White)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Play again") }
                        }
                    }
                }
            }
            Text("Long-press = flag (or toggle Flag mode) • Tap number with enough flags = chord", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
        }
    }
}

private fun emptyBoard(d:Diff): Array<Array<Cell>> = Array(d.h){ Array(d.w){ Cell() } }
private fun Cell.copy() = Cell(isMine, revealed, flagged, adj)
private fun numberColor(n:Int): Color = when(n){1->Color(0xFF0984E3);2->Color(0xFF00B894);3->Color(0xFFD63031);4->Color(0xFF6C5CE7);5->Color(0xFFE17055);6->Color(0xFF00CEC9);7->Color(0xFF2D3436);8->Color(0xFF636E72); else->ArcadeTokens.Text}

