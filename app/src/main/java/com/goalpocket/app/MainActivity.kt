package com.goalpocket.app

//aTODO: 설정 탭
//aTODO: 카테고리 관리
//aTODO: 다크모드 전환
//TODO: 친구 추가 및 관리
//TODO: 개인 목표 저축
//TODO: 공유 목표 저축
//TODO: 전월/전년 대비 비교
//TODO: 검색/필터
//TODO: 정기 결제 등록

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goalpocket.app.ui.theme.GoalPocketTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import kotlin.math.abs
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.Date
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextFieldDefaults

// 날짜 포맷
fun formatDate(ts: Timestamp?): String {
    if (ts == null) return "-"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(ts.toDate())
}

// 수입/지출 금액 표기
fun formatSignedAmount(amount: Long, type: String): String {
    val sign = if (type == "income") "+" else "-"
    return "$sign${"%,d".format(amount)}원"
}

// 순이익 표기
fun formatNetAmount(net: Long): String {
    val sign = when {
        net > 0 -> "+"
        net < 0 -> "-"
        else -> ""
    }
    return "$sign${"%,d".format(abs(net))}원"
}

// 기본 카테고리
fun defaultCategories(): List<String> =
    listOf("식비", "카페", "교통", "쇼핑", "기타")

data class TransactionItem(
    val id: String,
    val amount: Long,
    val memo: String,
    val type: String,
    val category: String,
    val date: Timestamp?
)

data class CategoryTotal(
    val category: String,
    val total: Long
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
            // ✅ 다크 모드 상태를 여기서 먼저 기억
            var isDarkMode by rememberSaveable { mutableStateOf(false) }

            // ✅ 테마에 darkTheme로 넘겨줌
            GoalPocketTheme(darkTheme = isDarkMode) {

                var screen by remember { mutableStateOf("login") }
                var selectedTransaction by remember { mutableStateOf<TransactionItem?>(null) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (screen) {
                        "login" -> LoginScreen(
                            onSignUpClick = { screen = "signup" },
                            onLoginSuccess = { screen = "home" }
                        )

                        "signup" -> SignUpScreen(
                            onBack = { screen = "login" }
                        )

                        "home" -> HomeScreen(
                            onLogout = {
                                FirebaseAuth.getInstance().signOut()
                                screen = "login"
                            },
                            onAddTransaction = { screen = "add" },
                            onOpenCalendar = { screen = "calendar" },
                            onOpenSettings = { screen = "settings" },
                            onSelectTransaction = { tx ->
                                selectedTransaction = tx
                                screen = "edit"
                            }
                        )

                        "add" -> AddTransactionScreen(
                            onSaved = { screen = "home" },
                            onCancel = { screen = "home" }
                        )

                        "edit" -> EditTransactionScreen(
                            transaction = selectedTransaction,
                            onSaved = { screen = "home" },
                            onDeleted = { screen = "home" },
                            onCancel = { screen = "home" }
                        )

                        "settings" -> SettingsScreen(
                            isDarkMode = isDarkMode,
                            onDarkModeChange = { isDarkMode = it },
                            onOpenCategorySettings = { screen = "category_settings" },
                            onOpenAbout = { screen = "about" },
                            onBack = { screen = "home" }
                        )

                        "category_settings" -> CategorySettingsScreen(
                            onBack = { screen = "settings" }
                        )

                        "about" -> AboutAppScreen(
                            onBack = { screen = "settings" }
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
                    Toast.makeText(context, "이메일과 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
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
            Text("회원가입")
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
                    Toast.makeText(context, "모든 값을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (password != confirmPassword) {
                    Toast.makeText(context, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (password.length < 6) {
                    Toast.makeText(context, "비밀번호는 6자 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
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
            Text(if (isLoading) "처리 중..." else "회원가입")
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
    onOpenCalendar: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSelectTransaction: (TransactionItem) -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var transactions by remember { mutableStateOf<List<TransactionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val now = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) }

    // 하단 탭 상태: 0=홈, 1=카테고리, 2=캘린더
    var selectedTab by remember { mutableStateOf(0) }

    // Firestore 로드
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
                Log.e("HomeScreen", "load error", e)
            }
    }

    // 선택된 월 데이터
    val filteredTransactions = remember(transactions, selectedYear, selectedMonth) {
        transactions.filter { tx ->
            val ts = tx.date ?: return@filter false
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.YEAR) == selectedYear &&
                    cal.get(Calendar.MONTH) == selectedMonth
        }
    }

    val incomeTotal = filteredTransactions
        .filter { it.type == "income" }
        .sumOf { it.amount }

    val expenseTotal = filteredTransactions
        .filter { it.type != "income" }
        .sumOf { it.amount }

    val netTotal = incomeTotal - expenseTotal
    val monthLabel = "%04d-%02d".format(selectedYear, selectedMonth + 1)

    // 카테고리별 지출 합계
    val categoryTotals = remember(filteredTransactions) {
        filteredTransactions
            .filter { it.type != "income" }
            .groupBy { it.category.ifBlank { "기타" } }
            .map { (cat, list) ->
                CategoryTotal(
                    category = cat,
                    total = list.sumOf { it.amount }
                )
            }
            .sortedByDescending { it.total }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GoalPocket") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "홈") },
                    label = { Text("홈") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PieChart, contentDescription = "카테고리") },
                    label = { Text("카테고리") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "캘린더") },
                    label = { Text("캘린더") }
                )
            }
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 월 이동
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

            when (selectedTab) {
                0 -> HomeMainTab(
                    monthLabel = monthLabel,
                    netTotal = netTotal,
                    filteredTransactions = filteredTransactions,
                    isLoading = isLoading,
                    onAddTransaction = onAddTransaction,
                    onSelectTransaction = onSelectTransaction
                )

                1 -> HomeCategoryTab(
                    categoryTotals = categoryTotals,
                    isLoading = isLoading
                )

                2 -> HomeCalendarTab(
                    year = selectedYear,
                    month = selectedMonth,
                    monthLabel = monthLabel,
                    monthTransactions = filteredTransactions
                )
            }
        }
    }
}

@Composable
fun HomeMainTab(
    monthLabel: String,
    netTotal: Long,
    filteredTransactions: List<TransactionItem>,
    isLoading: Boolean,
    onAddTransaction: () -> Unit,
    onSelectTransaction: (TransactionItem) -> Unit
) {
    val monthNumber = monthLabel.substring(5, 7).toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // 월 요약 카드 (수입 - 지출)
        SummaryCard(monthLabel = monthLabel, netTotal = netTotal)

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onAddTransaction,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("내역 추가")
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 🔹 제목: "12월 내역"
        Text(
            text = "상세 내역",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
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
                            .clickable { onSelectTransaction(tx) }
                            .padding(vertical = 8.dp)
                    ) {
                        // 첫 줄: 메모 + 금액
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tx.memo.ifBlank { "메모 없음" },
                                style = MaterialTheme.typography.bodyLarge
                            )

                            // 금액 표시 (+/- 및 콤마)
                            val formattedAmount = "%,d".format(kotlin.math.abs(tx.amount))
                            val amountText =
                                if (tx.type == "income") "+${formattedAmount}원"
                                else "-${formattedAmount}원"

                            val color = if (tx.type == "income")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error

                            Text(
                                text = amountText,
                                color = color
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // 두 번째 줄: 카테고리 + 날짜
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

@Composable
fun HomeCategoryTab(
    categoryTotals: List<CategoryTotal>,
    isLoading: Boolean
) {
    Text(
        text = "카테고리별 지출 합계",
        style = MaterialTheme.typography.titleMedium,
    )

    Spacer(modifier = Modifier.height(8.dp))

    if (isLoading) {
        CircularProgressIndicator()
        return
    }

    if (categoryTotals.isEmpty()) {
        Text("이 달 지출 내역이 없습니다.")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        categoryTotals.forEach { ct ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = ct.category,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "₩${"%,d".format(ct.total)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Divider()
        }
    }
}

@Composable
fun HomeCalendarTab(
    year: Int,
    month: Int, // 0~11
    monthLabel: String,
    monthTransactions: List<TransactionItem>
) {
    val dailyTotals: Map<Int, Long> = remember(monthTransactions) {
        monthTransactions.groupBy { tx ->
            val ts = tx.date ?: return@groupBy 0
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.DAY_OF_MONTH)
        }.mapValues { (_, list) ->
            list.sumOf { tx ->
                if (tx.type == "income") tx.amount else -tx.amount
            }
        }
    }

    var selectedDay by remember { mutableStateOf<Int?>(null) }

    val selectedDayTransactions = remember(monthTransactions, selectedDay) {
        if (selectedDay == null) emptyList() else
            monthTransactions.filter { tx ->
                val ts = tx.date ?: return@filter false
                val cal = Calendar.getInstance().apply { time = ts.toDate() }
                cal.get(Calendar.DAY_OF_MONTH) == selectedDay
            }
    }

    Text(
        text = "캘린더",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

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

    val cal = Calendar.getInstance().apply {
        set(year, month, 1)
    }
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
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
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (total != 0L) {
                                Text(
                                    text = formatNetAmount(total),
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

    Text(
        text = if (selectedDay == null)
            "날짜를 선택하세요."
        else
            "${monthLabel}-${"%02d".format(selectedDay)} 내역",
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
                        Text(formatSignedAmount(tx.amount, tx.type))
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

@Composable
fun TypeToggleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surface,
            contentColor = if (selected)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurface
        ),
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    var type by remember { mutableStateOf("expense") }

    var categories by remember { mutableStateOf(defaultCategories()) }
    var categoryLoading by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("기타") }

    var selectedDateMillis by remember {
        mutableStateOf<Long?>(System.currentTimeMillis())
    }

    val formattedDate = remember(selectedDateMillis) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        selectedDateMillis?.let { sdf.format(Date(it)) } ?: "날짜 선택"
    }

    var showCategorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
    )

    // 🔹 TextField 색상 오버라이드 (회색 현상 해결)
    val disabledLikeEnabledColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surface
    )

    // 🔹 카테고리 Firestore 불러오기
    LaunchedEffect(uid) {
        if (uid == null) return@LaunchedEffect

        categoryLoading = true

        val docRef = db.collection("users")
            .document(uid)
            .collection("settings")
            .document("categories")

        docRef.get()
            .addOnSuccessListener { doc ->
                val itemsAny = doc.get("items") as? List<*>
                val items = itemsAny?.mapNotNull { it as? String }
                    ?.distinct()
                    ?.filter { it.isNotBlank() }

                categories = if (!items.isNullOrEmpty()) items else defaultCategories()

                if (!categories.contains(selectedCategory)) {
                    selectedCategory = categories.firstOrNull() ?: "기타"
                }

                categoryLoading = false
            }
            .addOnFailureListener {
                categories = defaultCategories()
                categoryLoading = false
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("내역 추가", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(24.dp))

        // 🔷 타입 토글 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TypeToggleButton(
                text = "지출",
                selected = (type == "expense"),
                onClick = { type = "expense" },
                modifier = Modifier.weight(1f)
            )

            TypeToggleButton(
                text = "수입",
                selected = (type == "income"),
                onClick = { type = "income" },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

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

        Spacer(modifier = Modifier.height(12.dp))

        // 🔷 카테고리 선택
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !categoryLoading) { showCategorySheet = true }
        ) {
            OutlinedTextField(
                value = selectedCategory,
                onValueChange = {},
                label = { Text("카테고리") },
                readOnly = true,
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                },
                colors = disabledLikeEnabledColors
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 🔷 날짜 선택
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDatePicker = true }
        ) {
            OutlinedTextField(
                value = formattedDate,
                onValueChange = {},
                label = { Text("날짜") },
                readOnly = true,
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Icon(Icons.Default.CalendarToday, contentDescription = null)
                },
                colors = disabledLikeEnabledColors
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 🔷 저장 버튼
        Button(
            onClick = {
                if (uid == null) {
                    Toast.makeText(context, "로그인 정보 없음", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (amountText.isBlank()) {
                    Toast.makeText(context, "금액 입력해줘", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val amount = amountText.toLongOrNull() ?: 0
                if (amount <= 0) {
                    Toast.makeText(context, "금액이 올바르지 않아", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val ts = Timestamp(Date(selectedDateMillis ?: System.currentTimeMillis()))

                val data = mapOf(
                    "amount" to amount,
                    "type" to type,
                    "memo" to memo,
                    "category" to selectedCategory,
                    "date" to ts
                )

                db.collection("users")
                    .document(uid)
                    .collection("transactions")
                    .add(data)
                    .addOnSuccessListener {
                        Toast.makeText(context, "저장 완료", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "저장 실패", Toast.LENGTH_SHORT).show()
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("저장하기")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onCancel) {
            Text("취소")
        }
    }

    // 🔷 카테고리 모달
    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("카테고리 선택", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                if (categoryLoading) {
                    CircularProgressIndicator()
                } else {
                    categories.forEach { cat ->
                        ListItem(
                            headlineContent = { Text(cat) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCategory = cat
                                    scope.launch { sheetState.hide() }
                                        .invokeOnCompletion { showCategorySheet = false }
                                }
                        )
                    }
                }
            }
        }
    }

    // 🔷 날짜 선택 다이얼로그
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            selectedDateMillis = it
                        }
                        showDatePicker = false
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun SummaryCard(monthLabel: String, netTotal: Long) {
    val monthNumber = monthLabel.substring(5, 7).toInt()  // "2025-12" → 12

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
                text = "${monthNumber}월 합계",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatNetAmount(netTotal),
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    transaction: TransactionItem?,
    onSaved: () -> Unit = {},
    onDeleted: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    if (transaction == null || uid == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("수정할 내역을 찾을 수 없어.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onCancel) { Text("뒤로가기") }
        }
        return
    }

    var amountText by remember { mutableStateOf(transaction.amount.toString()) }
    var memo by remember { mutableStateOf(transaction.memo) }

    var type by remember { mutableStateOf(transaction.type.ifBlank { "expense" }) }

    var categories by remember { mutableStateOf(defaultCategories()) }
    var categoryLoading by remember { mutableStateOf(false) }
    var selectedCategory by remember {
        mutableStateOf(transaction.category.ifBlank { "기타" })
    }

    var selectedDateMillis by remember {
        mutableStateOf<Long?>(transaction.date?.toDate()?.time ?: System.currentTimeMillis())
    }

    val formattedDate = remember(selectedDateMillis) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        selectedDateMillis?.let { sdf.format(Date(it)) } ?: "날짜 선택"
    }

    var showCategorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
    )

    // 🔹 Disabled 색상 override
    val disabledLikeEnabledColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surface
    )

    // 🔹 카테고리 로드
    LaunchedEffect(uid) {
        if (uid == null) return@LaunchedEffect

        categoryLoading = true
        val docRef = db.collection("users")
            .document(uid)
            .collection("settings")
            .document("categories")

        docRef.get()
            .addOnSuccessListener { doc ->
                val itemsAny = doc.get("items") as? List<*>
                val items = itemsAny?.mapNotNull { it as? String }
                    ?.distinct()
                    ?.filter { it.isNotBlank() }

                categories = if (!items.isNullOrEmpty()) items else defaultCategories()

                if (!categories.contains(selectedCategory)) {
                    selectedCategory = categories.firstOrNull() ?: "기타"
                }

                categoryLoading = false
            }
            .addOnFailureListener {
                categories = defaultCategories()
                categoryLoading = false
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("내역 수정") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            db.collection("users")
                                .document(uid)
                                .collection("transactions")
                                .document(transaction.id)
                                .delete()
                                .addOnSuccessListener {
                                    Toast.makeText(context, "삭제 완료", Toast.LENGTH_SHORT).show()
                                    onDeleted()
                                }
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 🔷 타입 토글
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TypeToggleButton(
                    text = "지출",
                    selected = (type == "expense"),
                    onClick = { type = "expense" },
                    modifier = Modifier.weight(1f)
                )
                TypeToggleButton(
                    text = "수입",
                    selected = (type == "income"),
                    onClick = { type = "income" },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(12.dp))

            // 🔷 카테고리
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !categoryLoading) { showCategorySheet = true }
            ) {
                OutlinedTextField(
                    value = selectedCategory,
                    onValueChange = {},
                    label = { Text("카테고리") },
                    enabled = false,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    },
                    colors = disabledLikeEnabledColors
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 🔷 날짜
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            ) {
                OutlinedTextField(
                    value = formattedDate,
                    onValueChange = {},
                    label = { Text("날짜") },
                    enabled = false,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Icon(Icons.Default.CalendarToday, contentDescription = null)
                    },
                    colors = disabledLikeEnabledColors
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val amount = amountText.toLongOrNull() ?: 0
                    if (amount <= 0) {
                        Toast.makeText(context, "금액이 올바르지 않아", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val ts = Timestamp(Date(selectedDateMillis ?: System.currentTimeMillis()))

                    val data = mapOf(
                        "amount" to amount,
                        "memo" to memo,
                        "category" to selectedCategory,
                        "type" to type,
                        "date" to ts
                    )

                    db.collection("users")
                        .document(uid)
                        .collection("transactions")
                        .document(transaction.id)
                        .update(data)
                        .addOnSuccessListener {
                            Toast.makeText(context, "수정 완료", Toast.LENGTH_SHORT).show()
                            onSaved()
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("저장하기")
            }
        }
    }

    // 🔷 카테고리 시트
    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("카테고리 선택", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                if (categoryLoading) {
                    CircularProgressIndicator()
                } else {
                    categories.forEach { cat ->
                        ListItem(
                            headlineContent = { Text(cat) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCategory = cat
                                    scope.launch { sheetState.hide() }
                                        .invokeOnCompletion { showCategorySheet = false }
                                }
                        )
                    }
                }
            }
        }
    }

    // 🔷 날짜 선택 다이얼로그
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            selectedDateMillis = it
                        }
                        showDatePicker = false
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onOpenCategorySettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("다크 모드")
                    Text(
                        text = "앱 전체 색상 테마를 변경합니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = isDarkMode,
                    onCheckedChange = { checked ->
                        onDarkModeChange(checked)
                        Toast.makeText(
                            context,
                            if (checked) "다크 모드로 전환" else "라이트 모드로 전환",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenCategorySettings)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "지출/수입 카테고리",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "카테고리를 추가·삭제",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "카테고리 관리로 이동"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 앱 정보 화면으로 이동
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "앱 정보",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "버전, 개발자, 사용법 등",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "앱 정보로 이동"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySettingsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var categories by remember { mutableStateOf(defaultCategories()) }
    var newCategory by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        if (uid == null) return@LaunchedEffect
        isLoading = true

        val docRef = db.collection("users")
            .document(uid)
            .collection("settings")
            .document("categories")

        docRef.get()
            .addOnSuccessListener { doc ->
                val itemsAny = doc.get("items") as? List<*>
                val items = itemsAny
                    ?.mapNotNull { it as? String }
                    ?.distinct()
                    ?.filter { it.isNotBlank() }

                categories = if (items != null && items.isNotEmpty()) {
                    items
                } else {
                    defaultCategories()
                }

                if (!doc.exists() || items.isNullOrEmpty()) {
                    docRef.set(mapOf("items" to categories))
                }

                isLoading = false
            }
            .addOnFailureListener { e ->
                Log.e("CategorySettingsScreen", "load categories error", e)
                categories = defaultCategories()
                isLoading = false
            }
    }

    fun saveCategories(updated: List<String>) {
        if (uid == null) return
        db.collection("users")
            .document(uid)
            .collection("settings")
            .document("categories")
            .set(mapOf("items" to updated))
            .addOnFailureListener { e ->
                Log.e("CategorySettingsScreen", "save categories error", e)
                Toast.makeText(
                    context,
                    "카테고리 저장 실패: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카테고리 관리") },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            if (uid == null) {
                Text("로그인 상태에서만 카테고리를 관리할 수 있어.")
                return@Column
            }

            Text(
                text = "카테고리 추가",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { newCategory = it },
                    label = { Text("새 카테고리 이름") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        val trimmed = newCategory.trim()
                        if (trimmed.isBlank()) {
                            Toast.makeText(context, "카테고리 이름을 입력해줘", Toast.LENGTH_SHORT)
                                .show()
                            return@Button
                        }
                        if (categories.any { it == trimmed }) {
                            Toast.makeText(context, "이미 존재하는 카테고리야", Toast.LENGTH_SHORT)
                                .show()
                            return@Button
                        }

                        val updated = categories + trimmed
                        categories = updated
                        newCategory = ""
                        saveCategories(updated)
                        Toast.makeText(context, "카테고리가 추가되었어", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("추가")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "카테고리 목록",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                LazyColumn {
                    items(categories) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(cat)

                            TextButton(
                                onClick = {
                                    val updated = categories.filter { it != cat }
                                    categories = updated
                                    saveCategories(updated)
                                    Toast.makeText(
                                        context,
                                        "카테고리가 삭제되었어",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Text(
                                    text = "삭제",
                                    color = MaterialTheme.colorScheme.error
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAppScreen(
    onBack: () -> Unit = {}
) {
    val versionName = "1.0.0"
    val versionCode = 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("앱 정보") },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            // 1. 앱 버전 정보
            Text(
                text = "앱 버전",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "GoalPocket v$versionName (빌드 $versionCode)",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // 2. 개발자 정보
            Text(
                text = "개발자",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Jaeung Moon",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "이메일: luckymoon4157@gmail.com",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            // 3. 간단 사용법 안내
            Text(
                text = "앱 사용 안내",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• 홈 탭에서 이번 달 수입/지출 합계를 확인할 수 있습니다.\n" +
                        "• 카테고리는 설정 → 카테고리 관리에서 추가/삭제할 수 있습니다.\n" +
                        "• 캘린더 탭에서는 날짜별로 수입/지출 내역과 합계를 볼 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
