package com.goalpocket.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.goalpocket.app.ui.theme.GoalPocketTheme
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import java.util.Calendar
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.ArrowBack

fun formatDate(ts: com.google.firebase.Timestamp?): String {
    if (ts == null) return "-"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(ts.toDate())
}

data class TransactionItem(
    val id: String,
    val amount: Long,
    val memo: String,
    val type: String,
    val category: String,
    val date: com.google.firebase.Timestamp?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        Log.d(
            "FirebaseTest",
            "Firebase initialized: ${FirebaseApp.getApps(this).isNotEmpty()}"
        )

        setContent {
            GoalPocketTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var screen by remember { mutableStateOf("login") }

                    when (screen) {
                        "login" -> LoginScreen(
                            onSignUpClick = { screen = "signup" },
                            onLoginSuccess = { screen = "home" }
                        )

                        "signup" -> SignUpScreen(
                            onBack = { screen = "login" }
                        )

                        "home" -> HomeScreen(
                            onLogout = { screen = "login" },
                            onAddTransaction = { screen = "add" },
                            onOpenCalendar = { screen = "calendar" }
                        )

                        "add" -> AddTransactionScreen(
                            onSaved = { screen = "home" },
                            onCancel = { screen = "home" }
                        )

                        "calendar" -> CalendarScreen(
                            onBack = { screen = "home" }
                        )
                    }

                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onSignUpClick: () -> Unit = {},
    onLoginSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GoalPocket",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("이메일") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("비밀번호") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isLoading) return@Button

                if (email.isBlank() || password.isBlank()) {
                    Toast.makeText(context, "이메일과 비밀번호를 입력해줘", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true

                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        isLoading = false
                        if (task.isSuccessful) {
                            Toast.makeText(context, "로그인 성공", Toast.LENGTH_SHORT).show()
                            Log.d("LoginAuth", "로그인 성공: ${auth.currentUser?.uid}")
                            onLoginSuccess()
                        } else {
                            Log.e("LoginAuth", "로그인 실패", task.exception)
                            Toast.makeText(
                                context,
                                "로그인 실패: ${task.exception?.localizedMessage ?: "알 수 없는 오류"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "처리 중..." else "로그인")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onSignUpClick) {
            Text("회원가입 하러가기")
        }
    }
}

@Composable
fun SignUpScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "회원가입",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("이메일") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("비밀번호") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("비밀번호 확인") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isLoading) return@Button

                if (email.isBlank() || password.isBlank() || confirmPassword.isBlank()) {
                    Toast.makeText(context, "모든 값을 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (password != confirmPassword) {
                    Toast.makeText(context, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (password.length < 6) {
                    Toast.makeText(context, "비밀번호는 6자 이상이여야 합니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true

                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        isLoading = false
                        if (task.isSuccessful) {
                            Log.d("SignUpAuth", "회원가입 성공: ${auth.currentUser?.uid}")
                            Toast.makeText(context, "회원가입 성공", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Log.e("SignUpAuth", "회원가입 실패", task.exception)
                            Toast.makeText(
                                context,
                                "회원가입 실패: ${task.exception?.localizedMessage ?: "알 수 없는 오류"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "처리 중..." else "회원가입하기")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("뒤로가기")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogout: () -> Unit = {},
    onAddTransaction: () -> Unit = {},
    onOpenCalendar: () -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var transactions by remember { mutableStateOf<List<TransactionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // ✨ 선택된 월 (기본값: 현재 월)
    val now = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) }

    // Firestore 로드
    LaunchedEffect(uid) {
        if (uid == null) return@LaunchedEffect

        isLoading = true

        db.collection("users")
            .document(uid)
            .collection("transactions")
            .orderBy("date", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(200)
            .get()
            .addOnSuccessListener { snapshot ->
                transactions = snapshot.documents.map { doc ->
                    TransactionItem(
                        id = doc.id,
                        amount = doc.getLong("amount") ?: 0L,
                        memo = doc.getString("memo") ?: "",
                        type = doc.getString("type") ?: "",
                        category = doc.getString("category") ?: "",
                        date = doc.getTimestamp("date")
                    )
                }
                isLoading = false
            }
            .addOnFailureListener { e ->
                isLoading = false
                Toast.makeText(
                    context,
                    "내역 불러오기 실패: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e("HomeScreen", "load error", e)
            }
    }

    // ✨ 선택된 월의 데이터 필터링
    val filteredTransactions = remember(transactions, selectedYear, selectedMonth) {
        transactions.filter { tx ->
            val ts = tx.date ?: return@filter false
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.YEAR) == selectedYear &&
                    cal.get(Calendar.MONTH) == selectedMonth
        }
    }

    // ✨ 선택 월 합계
    val monthTotal = filteredTransactions.sumOf { it.amount }
    val monthLabel = "%04d-%02d".format(selectedYear, selectedMonth + 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GoalPocket") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // ✨ 월 이동 Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (selectedMonth == 0) {
                            selectedMonth = 11
                            selectedYear -= 1
                        } else {
                            selectedMonth -= 1
                        }
                    }
                ) { Text("◀") }

                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                TextButton(
                    onClick = {
                        if (selectedMonth == 11) {
                            selectedMonth = 0
                            selectedYear += 1
                        } else {
                            selectedMonth += 1
                        }
                    }
                ) { Text("▶") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ✨ 요약 카드
            SummaryCard(monthLabel = monthLabel, monthTotal = monthTotal)

            Spacer(modifier = Modifier.height(8.dp))

            // ✨ 지출 추가 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddTransaction,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("지출 추가")
                }

                OutlinedButton(
                    onClick = onOpenCalendar,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("캘린더 보기")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ✨ 리스트 제목
            Text(
                text = "내역 ($monthLabel)",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ✨ 리스트 표시
            if (isLoading) {
                CircularProgressIndicator()
            } else if (filteredTransactions.isEmpty()) {
                Text("이 달 등록된 내역이 없습니다.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredTransactions) { tx ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = tx.memo.ifBlank { "메모 없음" },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text("${tx.amount}원")
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = tx.category,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = formatDate(tx.date),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun AddTransactionScreen(
    onSaved: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var amountText by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("expense") } // 나중에 수입도 추가할 수 있음
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "지출 추가",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
            label = { Text("금액 (원)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            label = { Text("메모") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isLoading) return@Button

                if (uid == null) {
                    Toast.makeText(context, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (amountText.isBlank()) {
                    Toast.makeText(context, "금액을 입력해줘", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val amount = amountText.toLongOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(context, "금액이 올바르지 않아", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true

                val data = mapOf(
                    "amount" to amount,
                    "type" to type,
                    "category" to "기타", // 나중에 카테고리 시스템 만들면 연동
                    "memo" to memo,
                    "date" to com.google.firebase.Timestamp.now()
                )

                db.collection("users")
                    .document(uid)
                    .collection("transactions")
                    .add(data)
                    .addOnSuccessListener {
                        isLoading = false
                        Toast.makeText(context, "지출 저장 성공", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                    .addOnFailureListener { e ->
                        isLoading = false
                        Toast.makeText(
                            context,
                            "저장 실패: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e("AddTransaction", "Error", e)
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "저장 중..." else "저장하기")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onCancel) {
            Text("취소")
        }
    }
}

@Composable
fun SummaryCard(monthLabel: String, monthTotal: Long) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "$monthLabel 지출",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "₩${"%,d".format(monthTotal)}",
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var transactions by remember { mutableStateOf<List<TransactionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // 현재 월 기준으로 시작
    val now = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) } // 0~11
    var selectedDay by remember { mutableStateOf<Int?>(null) }

    // 전체 트랜잭션 로드 (HomeScreen과 비슷하게)
    LaunchedEffect(uid) {
        if (uid == null) return@LaunchedEffect

        isLoading = true

        db.collection("users")
            .document(uid)
            .collection("transactions")
            .orderBy("date", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(300)
            .get()
            .addOnSuccessListener { snapshot ->
                transactions = snapshot.documents.map { doc ->
                    TransactionItem(
                        id = doc.id,
                        amount = doc.getLong("amount") ?: 0L,
                        memo = doc.getString("memo") ?: "",
                        type = doc.getString("type") ?: "",
                        category = doc.getString("category") ?: "",
                        date = doc.getTimestamp("date")
                    )
                }
                isLoading = false
            }
            .addOnFailureListener { e ->
                isLoading = false
                Toast.makeText(
                    context,
                    "내역 불러오기 실패: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e("CalendarScreen", "load error", e)
            }
    }

    // 선택된 월의 트랜잭션 필터
    val monthTransactions = remember(transactions, selectedYear, selectedMonth) {
        transactions.filter { tx ->
            val ts = tx.date ?: return@filter false
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.YEAR) == selectedYear &&
                    cal.get(Calendar.MONTH) == selectedMonth
        }
    }

    // 일별 합계 계산
    val dailyTotals: Map<Int, Long> = remember(monthTransactions) {
        monthTransactions.groupBy { tx ->
            val ts = tx.date!!
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.DAY_OF_MONTH)
        }.mapValues { (_, list) ->
            list.sumOf { it.amount }
        }
    }

    // 선택된 날짜의 트랜잭션
    val selectedDayTransactions = remember(monthTransactions, selectedDay) {
        if (selectedDay == null) emptyList() else
            monthTransactions.filter { tx ->
                val ts = tx.date ?: return@filter false
                val cal = Calendar.getInstance().apply { time = ts.toDate() }
                cal.get(Calendar.DAY_OF_MONTH) == selectedDay
            }
    }

    val monthLabel = "%04d-%02d".format(selectedYear, selectedMonth + 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캘린더") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {

            // 월 이동 + 라벨
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (selectedMonth == 0) {
                            selectedMonth = 11
                            selectedYear -= 1
                        } else {
                            selectedMonth -= 1
                        }
                        selectedDay = null
                    }
                ) { Text("◀") }

                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                TextButton(
                    onClick = {
                        if (selectedMonth == 11) {
                            selectedMonth = 0
                            selectedYear += 1
                        } else {
                            selectedMonth += 1
                        }
                        selectedDay = null
                    }
                ) { Text("▶") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // 요일 헤더
            val weekDays = listOf("일", "월", "화", "수", "목", "금", "토")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                weekDays.forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 달력 그리드
            val cal = Calendar.getInstance().apply {
                set(selectedYear, selectedMonth, 1)
            }
            val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=일
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            val totalCells = firstDayOfWeek - 1 + daysInMonth
            val rows = (totalCells + 6) / 7

            Column {
                var day = 1
                for (r in 0 until rows) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (c in 0 until 7) {
                            val cellIndex = r * 7 + c
                            if (cellIndex < firstDayOfWeek - 1 || day > daysInMonth) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                ) { }
                            } else {
                                val today = day
                                val total = dailyTotals[today] ?: 0L
                                val isSelected = selectedDay == today

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clickable {
                                            selectedDay = today
                                        }
                                        .padding(2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = today.toString(),
                                        style = if (isSelected)
                                            MaterialTheme.typography.bodyMedium
                                        else
                                            MaterialTheme.typography.bodyMedium
                                    )
                                    if (total > 0) {
                                        Text(
                                            text = "₩${"%,d".format(total)}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                day++
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 선택된 날짜 내역 리스트
            Text(
                text = if (selectedDay == null) "날짜를 선택하면 내역이 보여" else "${monthLabel}-${"%02d".format(selectedDay)} 내역",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (selectedDay != null && selectedDayTransactions.isEmpty()) {
                Text("이 날짜에는 등록된 내역이 없습니다.")
            } else if (selectedDayTransactions.isNotEmpty()) {
                LazyColumn {
                    items(selectedDayTransactions) { tx ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = tx.memo.ifBlank { "메모 없음" },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text("${tx.amount}원")
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tx.category,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Divider()
                    }
                }
            }
        }
    }
}
