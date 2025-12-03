package com.goalpocket.app

//aTODO: 설정 탭
//aTODO: 카테고리 관리
//aTODO: 다크모드 전환
//TODO: 친구 추가 및 관리
//aTODO: 개인 목표 저축
//TODO: 공유 목표 저축
//TODO: 전월/전년 대비 비교
//TODO: 검색/필터
//aTODO: 정기 결제 등록
//TODO: 앱 잠금
//TODO: 숨김 카테고리

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import androidx.compose.material.icons.filled.Flag
import androidx.compose.ui.draw.clip

// 공통 날짜 포맷 함수 (Timestamp -> yyyy-MM-dd)
fun formatDate(ts: Timestamp?): String {
    if (ts == null) return "-"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(ts.toDate())
}

// 타입에 따라 부호가 붙은 금액 문자열을 생성
// ex) income, 10000 -> "+10,000원", expense, 5000 -> "-5,000원"
fun formatSignedAmount(amount: Long, type: String): String {
    val sign = if (type == "income") "+" else "-"
    return "$sign${"%,d".format(amount)}원"
}

// 순이익(수입-지출)을 표시용 문자열로 변환
// 양수: +, 음수: -, 0: 부호 없음
fun formatNetAmount(net: Long): String {
    val sign = when {
        net > 0 -> "+"
        net < 0 -> "-"
        else -> ""
    }
    return "$sign${"%,d".format(abs(net))}원"
}

// 사용자 설정이 없을 때 사용할 기본 카테고리 목록
fun defaultCategories(): List<String> =
    listOf("식비", "카페", "교통", "쇼핑", "기타")

// 정기결제를 포함해 특정 연/월에 보여줄 내역 리스트를 확장
fun expandTransactionsForMonth(
    baseTransactions: List<TransactionItem>,
    year: Int,
    month: Int // 0~11
): List<TransactionItem> {

    val result = mutableListOf<TransactionItem>()

    val monthStartCal = Calendar.getInstance().apply {
        set(year, month, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val monthStart = monthStartCal.timeInMillis

    val monthEndCal = (monthStartCal.clone() as Calendar).apply {
        add(Calendar.MONTH, 1)
    }
    val monthEnd = monthEndCal.timeInMillis

    for (tx in baseTransactions) {
        val baseDate = tx.date?.toDate() ?: continue
        val baseMillis = baseDate.time
        val endMillis = tx.recurringEndDate?.toDate()?.time

        // 정기결제가 아닌 일반 내역은 해당 월에만 포함
        if (!tx.isRecurring) {
            if (baseMillis in monthStart until monthEnd) {
                result.add(tx)
            }
            continue
        }

        val baseCal = Calendar.getInstance().apply { time = baseDate }

        when (tx.recurringInterval) {

            // 월 단위 정기결제
            "monthly" -> {
                val startYear = baseCal.get(Calendar.YEAR)
                val startMonth = baseCal.get(Calendar.MONTH)

                if (year < startYear || (year == startYear && month < startMonth)) continue

                val occCal = Calendar.getInstance().apply {
                    val day = baseCal.get(Calendar.DAY_OF_MONTH)
                    set(
                        year, month,
                        day,
                        baseCal.get(Calendar.HOUR_OF_DAY),
                        baseCal.get(Calendar.MINUTE),
                        baseCal.get(Calendar.SECOND)
                    )
                    set(Calendar.MILLISECOND, baseCal.get(Calendar.MILLISECOND))

                    val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                    if (day > maxDay) set(Calendar.DAY_OF_MONTH, maxDay)
                }

                val occMillis = occCal.timeInMillis

                if (occMillis < baseMillis) continue
                if (endMillis != null && occMillis > endMillis) continue
                if (occMillis !in monthStart until monthEnd) continue

                result.add(tx.copy(occurrenceDate = Timestamp(Date(occMillis))))
            }

            // 연 단위 정기결제
            "yearly" -> {
                val startYear = baseCal.get(Calendar.YEAR)
                val startMonth = baseCal.get(Calendar.MONTH)

                if (month != startMonth) continue
                if (year < startYear) continue

                val day = baseCal.get(Calendar.DAY_OF_MONTH)

                val occCal = Calendar.getInstance().apply {
                    set(
                        year, month,
                        day,
                        baseCal.get(Calendar.HOUR_OF_DAY),
                        baseCal.get(Calendar.MINUTE),
                        baseCal.get(Calendar.SECOND)
                    )
                    set(Calendar.MILLISECOND, baseCal.get(Calendar.MILLISECOND))

                    val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                    if (day > maxDay) set(Calendar.DAY_OF_MONTH, maxDay)
                }

                val occMillis = occCal.timeInMillis

                if (occMillis < baseMillis) continue
                if (endMillis != null && occMillis > endMillis) continue
                if (occMillis !in monthStart until monthEnd) continue

                result.add(tx.copy(occurrenceDate = Timestamp(Date(occMillis))))
            }

            // 주 단위 정기결제
            "weekly" -> {
                val baseDow = baseCal.get(Calendar.DAY_OF_WEEK)

                val occCal = Calendar.getInstance().apply {
                    timeInMillis = maxOf(baseMillis, monthStart)
                }

                while (
                    occCal.timeInMillis < monthEnd &&
                    occCal.get(Calendar.DAY_OF_WEEK) != baseDow
                ) {
                    occCal.add(Calendar.DAY_OF_MONTH, 1)
                }

                while (occCal.timeInMillis in monthStart until monthEnd) {
                    val occMillis = occCal.timeInMillis

                    if (occMillis >= baseMillis &&
                        (endMillis == null || occMillis <= endMillis)
                    ) {
                        result.add(
                            tx.copy(occurrenceDate = Timestamp(Date(occMillis)))
                        )
                    }

                    occCal.add(Calendar.DAY_OF_MONTH, 7)
                }
            }

            // interval이 정의되지 않은 경우: 일반 단일 내역처럼 처리
            else -> {
                if (baseMillis in monthStart until monthEnd) {
                    result.add(tx)
                }
            }
        }
    }

    return result.sortedByDescending {
        (it.occurrenceDate ?: it.date)?.toDate()?.time ?: 0L
    }
}

// Firestore의 단일 가계부 내역 구조
data class TransactionItem(
    val id: String,
    val amount: Long,
    val memo: String,
    val type: String,              // "income" or "expense"
    val category: String,
    val date: Timestamp?,          // 원본 기준 날짜
    val isRecurring: Boolean = false,
    val recurringInterval: String? = null,   // "weekly", "monthly", "yearly"
    val recurringEndDate: Timestamp? = null, // 반복 종료일
    val occurrenceDate: Timestamp? = null    // 가상 내역용 실제 발생일
)

// 카테고리별 합계를 표현하는 데이터 모델
data class CategoryTotal(
    val category: String,
    val total: Long
)

data class GoalItem(
    val id: String,
    val name: String,
    val targetAmount: Long,
    val category: String,
    val deadline: Timestamp?,
    val currentAmount: Long
)

data class GoalRecordItem(
    val id: String,
    val amount: Long,
    val memo: String,
    val type: String,          // "add" 또는 "withdraw"
    val date: Timestamp?,
    val goalName: String = ""  // 홈 탭 표기를 위한 목표 이름
)

// 앱 엔트리 포인트 Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        Log.d(
            "FirebaseTest",
            "Firebase initialized: ${FirebaseApp.getApps(this).isNotEmpty()}"
        )

        setContent {
            var isDarkMode by rememberSaveable { mutableStateOf(false) }

            GoalPocketTheme(darkTheme = isDarkMode) {

                var screen by remember { mutableStateOf("login") }
                var selectedTransaction by remember { mutableStateOf<TransactionItem?>(null) }
                var selectedGoal by remember { mutableStateOf<GoalItem?>(null) }

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
                            },
                            onOpenGoalDetail = { goal ->
                                selectedGoal = goal
                                screen = "goal_detail"
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

                        "goal_detail" -> GoalDetailScreen(
                            goal = selectedGoal,
                            onBack = { screen = "home" }
                        )
                    }
                }
            }
        }
    }
}

// 이메일/비밀번호 기반 로그인 화면
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
                    Toast.makeText(
                        context,
                        "이메일과 비밀번호를 입력해 주세요.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@Button
                }

                isLoading = true

                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        isLoading = false
                        if (task.isSuccessful) {
                            Toast.makeText(context, "로그인되었습니다.", Toast.LENGTH_SHORT).show()
                            Log.d("LoginAuth", "로그인 성공: ${auth.currentUser?.uid}")
                            onLoginSuccess()
                        } else {
                            Log.e("LoginAuth", "로그인 실패", task.exception)
                            Toast.makeText(
                                context,
                                "로그인에 실패했습니다: ${task.exception?.localizedMessage ?: "알 수 없는 오류입니다."}",
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

// Firebase 이메일/비밀번호 회원가입 화면
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
                    Toast.makeText(context, "모든 값을 입력해 주세요.", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(
                                context,
                                "회원가입이 완료되었습니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                            onBack()
                        } else {
                            Log.e("SignUpAuth", "회원가입 실패", task.exception)
                            Toast.makeText(
                                context,
                                "회원가입에 실패했습니다: ${task.exception?.localizedMessage ?: "알 수 없는 오류입니다."}",
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
            Text("뒤로 가기")
        }
    }
}

// 로그인 이후 메인 홈 화면 (하단 탭 + 월 이동)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogout: () -> Unit = {},
    onAddTransaction: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSelectTransaction: (TransactionItem) -> Unit = {},
    onOpenGoalDetail: (GoalItem) -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var transactions by remember { mutableStateOf<List<TransactionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var goalRecords by remember { mutableStateOf<List<GoalRecordItem>>(emptyList()) }
    var goalRecordsLoading by remember { mutableStateOf(false) }

    val now = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) } // 0~11

    var selectedTab by remember { mutableStateOf(0) }

    // 상단 연·월을 눌러서 여는 캘린더 다이얼로그 상태
    var showMonthPicker by remember { mutableStateOf(false) }
    val monthPickerState = rememberDatePickerState(
        initialSelectedDateMillis = Calendar.getInstance().apply {
            set(selectedYear, selectedMonth, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    )

    DisposableEffect(uid) {
        if (uid == null) {
            transactions = emptyList()
            onDispose { }
        } else {
            isLoading = true

            val registration: ListenerRegistration =
                db.collection("users")
                    .document(uid)
                    .collection("transactions")
                    .orderBy("date", Query.Direction.DESCENDING)
                    .limit(300)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            isLoading = false
                            Log.e("HomeScreen", "snapshot error", e)
                            Toast.makeText(
                                context,
                                "내역을 불러오지 못했습니다: ${e.localizedMessage}",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@addSnapshotListener
                        }

                        if (snapshot != null) {
                            transactions = snapshot.documents.map { doc ->
                                TransactionItem(
                                    id = doc.id,
                                    amount = doc.getLong("amount") ?: 0L,
                                    memo = doc.getString("memo") ?: "",
                                    type = doc.getString("type") ?: "",
                                    category = doc.getString("category") ?: "",
                                    date = doc.getTimestamp("date"),
                                    isRecurring = doc.getBoolean("isRecurring") ?: false,
                                    recurringInterval = doc.getString("recurringInterval"),
                                    recurringEndDate = doc.getTimestamp("recurringEndDate")
                                )
                            }
                            isLoading = false
                        }
                    }

            onDispose {
                registration.remove()
            }
        }
    }

    DisposableEffect(uid) {
        if (uid == null) {
            goalRecords = emptyList()
            onDispose { }
        } else {
            goalRecordsLoading = true

            val registration = FirebaseFirestore.getInstance()
                .collectionGroup("records")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        goalRecordsLoading = false
                        Log.e("HomeScreen", "goal records snapshot error", e)
                        Toast.makeText(
                            context,
                            "목표 기록을 불러오는 중 오류가 발생했습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        goalRecords = snapshot.documents.map { doc ->
                            GoalRecordItem(
                                id = doc.id,
                                amount = doc.getLong("amount") ?: 0L,
                                memo = doc.getString("memo") ?: "",
                                type = doc.getString("type") ?: "add",
                                date = doc.getTimestamp("date"),
                                goalName = doc.getString("goalName") ?: ""
                            )
                        }
                        goalRecordsLoading = false
                    }
                }

            onDispose {
                registration.remove()
            }
        }
    }

    val filteredTransactions = remember(transactions, selectedYear, selectedMonth) {
        expandTransactionsForMonth(
            baseTransactions = transactions,
            year = selectedYear,
            month = selectedMonth
        )
    }

    val goalRecordsForMonth = remember(goalRecords, selectedYear, selectedMonth) {
        val cal = Calendar.getInstance()
        goalRecords.filter { r ->
            val ts = r.date ?: return@filter false
            val d = ts.toDate()
            cal.time = d
            cal.get(Calendar.YEAR) == selectedYear &&
                    cal.get(Calendar.MONTH) == selectedMonth
        }.sortedByDescending { it.date?.toDate()?.time ?: 0L }
    }

    val incomeTotal = filteredTransactions
        .filter { it.type == "income" }
        .sumOf { it.amount }

    val expenseTotal = filteredTransactions
        .filter { it.type != "income" }
        .sumOf { it.amount }

    val netTotal = incomeTotal - expenseTotal
    val monthLabel = "%04d-%02d".format(selectedYear, selectedMonth + 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GoalPocket") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "로그아웃")
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
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Flag, contentDescription = "목표") },
                    label = { Text("목표") }
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

            // 상단 연·월 선택 영역 (좌우 화살표 + 가운데 텍스트 클릭 시 캘린더)
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
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable { showMonthPicker = true }
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
                    onSelectTransaction = onSelectTransaction,
                    goalRecords = goalRecordsForMonth
                )


                1 -> HomeCategoryTab(
                    monthTransactions = filteredTransactions,
                    isLoading = isLoading
                )

                2 -> HomeCalendarTab(
                    year = selectedYear,
                    month = selectedMonth,
                    monthLabel = monthLabel,
                    monthTransactions = filteredTransactions,
                    goalRecords = goalRecordsForMonth,
                    onSelectTransaction = onSelectTransaction
                )

                3 -> GoalsTab(
                    onOpenGoalDetail = onOpenGoalDetail
                )
            }
        }
    }

    // 상단 연·월 텍스트 클릭 시 띄우는 캘린더 다이얼로그
    if (showMonthPicker) {
        DatePickerDialog(
            onDismissRequest = { showMonthPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = monthPickerState.selectedDateMillis
                        if (millis != null) {
                            val cal = Calendar.getInstance().apply { timeInMillis = millis }
                            selectedYear = cal.get(Calendar.YEAR)
                            selectedMonth = cal.get(Calendar.MONTH) // 0~11
                        }
                        showMonthPicker = false
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showMonthPicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = monthPickerState)
        }
    }
}

// 홈 탭: 월 합계와 상세 내역 리스트
@Composable
fun HomeMainTab(
    monthLabel: String,
    netTotal: Long,
    filteredTransactions: List<TransactionItem>,
    isLoading: Boolean,
    onAddTransaction: () -> Unit,
    onSelectTransaction: (TransactionItem) -> Unit,
    goalRecords: List<GoalRecordItem>
) {
    val monthNumber = monthLabel.substring(5, 7).toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        SummaryCard(monthLabel = monthLabel, netTotal = netTotal)

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onAddTransaction,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("내역 추가")
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                // 1) 상세 내역 헤더
                item {
                    Text(
                        text = "상세 내역",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 2) 일반 내역 리스트 또는 "없음" 메시지
                if (filteredTransactions.isEmpty()) {
                    item {
                        Text("이 달 등록된 내역이 없습니다.")
                    }
                } else {
                    items(filteredTransactions) { tx ->
                        val displayDate = tx.occurrenceDate ?: tx.date

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTransaction(tx) }
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

                                val formattedAmount = "%,d".format(abs(tx.amount))
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tx.category,
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    if (tx.isRecurring) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "정기결제",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = formatDate(displayDate),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Divider()
                    }
                }

                // 3) 목표 입출금 내역 헤더
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "목표 입출금 내역",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (goalRecords.isEmpty()) {
                        Text(
                            text = "이 달에는 목표 입출금 기록이 없습니다.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 4) 목표 입출금 기록 리스트
                if (goalRecords.isNotEmpty()) {
                    items(goalRecords) { r ->
                        val sign = if (r.type == "add") "+" else "-"
                        val color =
                            if (r.type == "add") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = r.memo.ifBlank { "메모 없음" },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (r.goalName.isBlank())
                                            "목표: 이름 없음"
                                        else
                                            "목표: ${r.goalName}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Text(
                                    text = "$sign${"%,d".format(r.amount)}원",
                                    color = color
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = formatDate(r.date),
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

// 카테고리 탭: 카테고리별 합계 및 정기결제 목록
@Composable
fun HomeCategoryTab(
    monthTransactions: List<TransactionItem>,
    isLoading: Boolean
) {
    Text(
        text = "카테고리별 합계",
        style = MaterialTheme.typography.titleMedium,
    )

    Spacer(modifier = Modifier.height(8.dp))

    if (isLoading) {
        CircularProgressIndicator()
        return
    }

    val categoryTotals = remember(monthTransactions) {
        monthTransactions
            .groupBy { it.category.ifBlank { "기타" } }
            .map { (cat, list) ->
                val total = list.sumOf { tx ->
                    if (tx.type == "income") tx.amount else -tx.amount
                }
                CategoryTotal(
                    category = cat,
                    total = total
                )
            }
            .sortedByDescending { it.total }
    }

    if (categoryTotals.isEmpty()) {
        Text("이 달 내역이 없습니다.")
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            categoryTotals.forEachIndexed { index, ct ->
                val amountText = formatNetAmount(ct.total)
                val amountColor = when {
                    ct.total > 0 -> MaterialTheme.colorScheme.primary
                    ct.total < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }

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
                        text = amountText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = amountColor
                    )
                }

                if (index < categoryTotals.size - 1) {
                    Divider()
                }
            }
        }
    }

    val recurringTransactions = remember(monthTransactions) {
        monthTransactions.filter { it.isRecurring }
    }

    if (recurringTransactions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "정기결제 목록",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            recurringTransactions.forEachIndexed { index, tx ->

                val effectiveType =
                    if (tx.type.isBlank()) "expense" else tx.type
                val amountText = formatSignedAmount(tx.amount, effectiveType)
                val amountColor =
                    if (effectiveType == "income") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error

                val displayDate = tx.occurrenceDate ?: tx.date

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tx.memo.ifBlank { "메모 없음" },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = amountText,
                            color = amountColor
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tx.category,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "정기결제",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Text(
                            text = formatDate(displayDate),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (index < recurringTransactions.size - 1) {
                    Divider()
                }
            }
        }
    }
}

// 캘린더 탭: 날짜별 합계와 일자별 상세 내역
@Composable
fun HomeCalendarTab(
    year: Int,
    month: Int,
    monthLabel: String,
    monthTransactions: List<TransactionItem>,
    goalRecords: List<GoalRecordItem>,
    onSelectTransaction: (TransactionItem) -> Unit
) {
    val dailyTotals: Map<Int, Long> = remember(monthTransactions) {
        monthTransactions
            .groupBy { tx ->
                val ts = (tx.occurrenceDate ?: tx.date) ?: return@groupBy 0
                val cal = Calendar.getInstance().apply { time = ts.toDate() }
                cal.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { (_, list) ->
                list.sumOf { tx ->
                    if (tx.type == "income") tx.amount else -tx.amount
                }
            }
    }

    var selectedDay by remember { mutableStateOf<Int?>(null) }

    val selectedDayTransactions = remember(monthTransactions, selectedDay) {
        if (selectedDay == null) emptyList()
        else monthTransactions.filter { tx ->
            val ts = (tx.occurrenceDate ?: tx.date) ?: return@filter false
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.DAY_OF_MONTH) == selectedDay
        }
    }

    // 선택된 날짜의 목표 입출금 기록
    val selectedDayGoalRecords = remember(goalRecords, selectedDay) {
        if (selectedDay == null) emptyList()
        else goalRecords.filter { r ->
            val ts = r.date ?: return@filter false
            val cal = Calendar.getInstance().apply { time = ts.toDate() }
            cal.get(Calendar.DAY_OF_MONTH) == selectedDay
        }
    }

    Text(
        text = "캘린더",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(12.dp))
    Divider()
    Spacer(modifier = Modifier.height(12.dp))

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

    Spacer(modifier = Modifier.height(12.dp))
    Divider()
    Spacer(modifier = Modifier.height(6.dp))

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

                        val amountColor = when {
                            total > 0 -> MaterialTheme.colorScheme.primary
                            total < 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }

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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = amountColor
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
    Divider()
    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = if (selectedDay == null)
            "날짜를 선택해 주세요."
        else
            "${monthLabel}-${"%02d".format(selectedDay)} 내역",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    if (selectedDay == null) {
        Text(
            text = "아래 캘린더에서 날짜를 선택하시면 해당 날짜의 내역이 표시됩니다.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    if (selectedDayTransactions.isEmpty() && selectedDayGoalRecords.isEmpty()) {
        Text("이 날짜에는 등록된 내역이 없습니다.")
        return
    }

    LazyColumn {
        // 1) 일반 내역 섹션
        item {
            Text(
                text = "상세 내역",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (selectedDayTransactions.isEmpty()) {
            item {
                Text(
                    text = "이 날짜에는 일반 내역이 없습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            items(selectedDayTransactions) { tx ->
                val effectiveType =
                    if (tx.type.isBlank()) "expense" else tx.type
                val amountText = formatSignedAmount(tx.amount, effectiveType)
                val amountColor =
                    if (effectiveType == "income") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTransaction(tx) }
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
                        Text(
                            text = amountText,
                            color = amountColor
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tx.category,
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (tx.isRecurring) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "정기결제",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Divider()
            }
        }

        // 2) 목표 입출금 내역 섹션
        item {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "목표 입출금 내역",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (selectedDayGoalRecords.isEmpty()) {
                Text(
                    text = "이 날짜에는 목표 입출금 기록이 없습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (selectedDayGoalRecords.isNotEmpty()) {
            items(selectedDayGoalRecords) { r ->
                val sign = if (r.type == "add") "+" else "-"
                val color =
                    if (r.type == "add") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = r.memo.ifBlank { "메모 없음" },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (r.goalName.isBlank())
                                    "목표: 이름 없음"
                                else
                                    "목표: ${r.goalName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Text(
                            text = "$sign${"%,d".format(r.amount)}원",
                            color = color
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = formatDate(r.date),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Divider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsTab(
    onOpenGoalDetail: (GoalItem) -> Unit
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var goals by remember { mutableStateOf<List<GoalItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newTargetAmountText by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }

    var newDeadlineMillis by remember { mutableStateOf<Long?>(null) }
    var showDeadlinePicker by remember { mutableStateOf(false) }
    val deadlinePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )

    var categories by remember { mutableStateOf(defaultCategories()) }
    var categoryLoading by remember { mutableStateOf(false) }
    var selectedCategoryForNewGoal by remember { mutableStateOf("기타") }

    var showCategorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var goalPendingDelete by remember { mutableStateOf<GoalItem?>(null) }
    var isDeletingGoal by remember { mutableStateOf(false) }

    val formattedDeadline = remember(newDeadlineMillis) {
        if (newDeadlineMillis == null) {
            "마감일 없음"
        } else {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(Date(newDeadlineMillis!!))
        }
    }

    DisposableEffect(uid) {
        if (uid == null) {
            goals = emptyList()
            onDispose { }
        } else {
            isLoading = true

            val registration = db.collection("users")
                .document(uid)
                .collection("goals")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        isLoading = false
                        Log.e("GoalsTab", "snapshot error", e)
                        Toast.makeText(
                            context,
                            "목표를 불러오는 중 오류가 발생했습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        goals = snapshot.documents.map { doc ->
                            GoalItem(
                                id = doc.id,
                                name = doc.getString("name") ?: "",
                                targetAmount = doc.getLong("targetAmount") ?: 0L,
                                category = doc.getString("category") ?: "",
                                deadline = doc.getTimestamp("deadline"),
                                currentAmount = doc.getLong("currentAmount") ?: 0L
                            )
                        }
                        isLoading = false
                    }
                }

            onDispose {
                registration.remove()
            }
        }
    }

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
                val items = itemsAny
                    ?.mapNotNull { it as? String }
                    ?.distinct()
                    ?.filter { it.isNotBlank() }

                categories = if (!items.isNullOrEmpty()) items else defaultCategories()

                if (!categories.contains(selectedCategoryForNewGoal)) {
                    selectedCategoryForNewGoal = categories.firstOrNull() ?: "기타"
                }

                categoryLoading = false
            }
            .addOnFailureListener {
                categories = defaultCategories()
                categoryLoading = false
            }
    }

    fun deleteGoalWithRecords(goal: GoalItem) {
        if (uid == null) return

        val goalRef = db.collection("users")
            .document(uid)
            .collection("goals")
            .document(goal.id)

        isDeletingGoal = true

        goalRef.collection("records")
            .get()
            .addOnSuccessListener { snapshot ->
                db.runBatch { batch ->
                    snapshot.documents.forEach { doc ->
                        batch.delete(doc.reference)
                    }
                    batch.delete(goalRef)
                }.addOnSuccessListener {
                    isDeletingGoal = false
                    Toast.makeText(
                        context,
                        "목표와 연결된 입출금 기록이 모두 삭제되었습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }.addOnFailureListener { e ->
                    isDeletingGoal = false
                    Log.e("GoalsTab", "delete goal batch error", e)
                    Toast.makeText(
                        context,
                        "목표 삭제 중 오류가 발생했습니다: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener { e ->
                isDeletingGoal = false
                Log.e("GoalsTab", "load records for delete error", e)
                Toast.makeText(
                    context,
                    "목표의 입출금 기록을 불러오는 중 오류가 발생했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "목표",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = { showAddDialog = true }
            ) {
                Text("목표 추가")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            uid == null -> {
                Text("로그인 후 목표를 관리하실 수 있습니다.")
            }

            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            goals.isEmpty() -> {
                Text("등록된 목표가 없습니다. \"목표 추가\" 버튼을 눌러 새 목표를 만들어 보세요.")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(goals) { goal ->
                        GoalListItem(
                            goal = goal,
                            onClick = { onOpenGoalDetail(goal) },
                            onDelete = {
                                // 예전: 여기서 바로 Firestore delete 호출
                                // 새로: 확인 다이얼로그를 띄우기 위해 상태만 설정
                                goalPendingDelete = goal
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (uid == null) {
                            Toast.makeText(context, "로그인 후 이용해 주세요.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }

                        val name = newName.trim()
                        val targetAmount = newTargetAmountText.toLongOrNull() ?: 0L
                        val category = newCategory.trim()

                        if (name.isEmpty()) {
                            Toast.makeText(context, "목표 이름을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (targetAmount <= 0) {
                            Toast.makeText(
                                context,
                                "목표 금액은 0원보다 커야 합니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }

                        val data = mutableMapOf<String, Any>(
                            "name" to name,
                            "targetAmount" to targetAmount,
                            "category" to category,
                            "currentAmount" to 0L
                        )

                        if (newDeadlineMillis != null) {
                            data["deadline"] = Timestamp(Date(newDeadlineMillis!!))
                        }

                        db.collection("users")
                            .document(uid)
                            .collection("goals")
                            .add(data)
                            .addOnSuccessListener {
                                Toast.makeText(
                                    context,
                                    "목표가 추가되었습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                newName = ""
                                newTargetAmountText = ""
                                newDeadlineMillis = null
                                // 카테고리는 기본값으로 리셋 (있으면 첫 번째, 없으면 "기타")
                                selectedCategoryForNewGoal = categories.firstOrNull() ?: "기타"
                                showAddDialog = false
                            }
                            .addOnFailureListener { ex ->
                                Toast.makeText(
                                    context,
                                    "목표 추가 중 오류가 발생했습니다: ${ex.localizedMessage}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("취소")
                }
            },
            title = { Text("새 목표 추가") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("목표 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newTargetAmountText,
                        onValueChange = { newTargetAmountText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("목표 금액 (원)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !categoryLoading) { showCategorySheet = true }
                    ) {
                        OutlinedTextField(
                            value = selectedCategoryForNewGoal,
                            onValueChange = {},
                            label = { Text("카테고리") },
                            readOnly = true,
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeadlinePicker = true }
                    ) {
                        OutlinedTextField(
                            value = formattedDeadline,
                            onValueChange = {},
                            label = { Text("마감일 (선택)") },
                            readOnly = true,
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }
        )
    }

    if (showDeadlinePicker) {
        DatePickerDialog(
            onDismissRequest = { showDeadlinePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadlinePickerState.selectedDateMillis?.let {
                            newDeadlineMillis = it
                        }
                        showDeadlinePicker = false
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeadlinePicker = false }) {
                    Text("취소")
                }
            }
        ) {
            DatePicker(state = deadlinePickerState)
        }
    }

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
                                    selectedCategoryForNewGoal = cat
                                    scope.launch { sheetState.hide() }
                                        .invokeOnCompletion { showCategorySheet = false }
                                }
                        )
                    }
                }
            }
        }
    }

    if (goalPendingDelete != null) {
        val target = goalPendingDelete!!
        AlertDialog(
            onDismissRequest = {
                if (!isDeletingGoal) {
                    goalPendingDelete = null
                }
            },
            title = { Text("목표를 삭제하시겠습니까?") },
            text = {
                Text(
                    "이 목표를 삭제하시면 해당 목표에 등록된 입출금 기록도 함께 삭제됩니다.\n" +
                            "삭제하신 내용은 되돌릴 수 없습니다."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!isDeletingGoal) {
                            goalPendingDelete = null
                            deleteGoalWithRecords(target)
                        }
                    },
                    enabled = !isDeletingGoal
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isDeletingGoal) {
                            goalPendingDelete = null
                        }
                    }
                ) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun GoalListItem(
    goal: GoalItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val deadlineText = formatDate(goal.deadline)
    val currentText = "%,d원".format(goal.currentAmount)
    val targetText = "%,d원".format(goal.targetAmount)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = goal.name.ifBlank { "이름 없는 목표" },
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (goal.category.isBlank()) "카테고리 없음" else goal.category,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (goal.deadline == null) "마감일 없음" else "마감일: $deadlineText",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "목표 삭제"
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$currentText / $targetText",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(4.dp))

        GoalProgressBar(
            currentAmount = goal.currentAmount,
            targetAmount = goal.targetAmount
        )
    }
}

// 지출/수입, 일반/정기결제 등에 공통으로 사용하는 토글 버튼
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

// 내역 추가 화면
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

    var isRecurring by remember { mutableStateOf(false) }
    var recurringInterval by remember { mutableStateOf("monthly") }
    var hasEndDate by remember { mutableStateOf(false) }
    var endDateMillis by remember { mutableStateOf<Long?>(null) }

    val formattedEndDate = remember(hasEndDate, endDateMillis) {
        if (!hasEndDate || endDateMillis == null) {
            "없음"
        } else {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(Date(endDateMillis!!))
        }
    }

    var showCategorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
    )

    var showEndDatePicker by remember { mutableStateOf(false) }
    val endDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = endDateMillis ?: System.currentTimeMillis()
    )

    val disabledLikeEnabledColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surface
    )

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

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "결제 유형",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TypeToggleButton(
                text = "일반",
                selected = !isRecurring,
                onClick = {
                    isRecurring = false
                    hasEndDate = false
                    endDateMillis = null
                },
                modifier = Modifier.weight(1f)
            )

            TypeToggleButton(
                text = "정기 결제",
                selected = isRecurring,
                onClick = { isRecurring = true },
                modifier = Modifier.weight(1f)
            )
        }

        if (isRecurring) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "반복 주기",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TypeToggleButton(
                    text = "주",
                    selected = recurringInterval == "weekly",
                    onClick = { recurringInterval = "weekly" },
                    modifier = Modifier.weight(1f)
                )
                TypeToggleButton(
                    text = "월",
                    selected = recurringInterval == "monthly",
                    onClick = { recurringInterval = "monthly" },
                    modifier = Modifier.weight(1f)
                )
                TypeToggleButton(
                    text = "년",
                    selected = recurringInterval == "yearly",
                    onClick = { recurringInterval = "yearly" },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("마감일")
                    Text(
                        text = "기본값은 '없음'이며, 설정한 경우에만 적용됩니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = hasEndDate,
                    onCheckedChange = { checked ->
                        hasEndDate = checked
                        if (!checked) {
                            endDateMillis = null
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .let {
                        if (hasEndDate) it.clickable { showEndDatePicker = true }
                        else it
                    }
            ) {
                OutlinedTextField(
                    value = formattedEndDate,
                    onValueChange = {},
                    label = { Text("마감일") },
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (hasEndDate) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null)
                        }
                    },
                    colors = disabledLikeEnabledColors
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (uid == null) {
                    Toast.makeText(context, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (amountText.isBlank()) {
                    Toast.makeText(context, "금액을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val amount = amountText.toLongOrNull() ?: 0
                if (amount <= 0) {
                    Toast.makeText(context, "유효한 금액이 아닙니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val ts = Timestamp(Date(selectedDateMillis ?: System.currentTimeMillis()))

                val data = mutableMapOf<String, Any>(
                    "amount" to amount,
                    "type" to type,
                    "memo" to memo,
                    "category" to selectedCategory,
                    "date" to ts,
                    "isRecurring" to isRecurring
                )

                if (isRecurring) {
                    data["recurringInterval"] = recurringInterval
                    if (hasEndDate && endDateMillis != null) {
                        data["recurringEndDate"] = Timestamp(Date(endDateMillis!!))
                    }
                }

                db.collection("users")
                    .document(uid)
                    .collection("transactions")
                    .add(data)
                    .addOnSuccessListener {
                        Toast.makeText(context, "저장되었습니다.", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
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

    if (showEndDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        endDatePickerState.selectedDateMillis?.let {
                            endDateMillis = it
                            hasEndDate = true
                        }
                        showEndDatePicker = false
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = endDatePickerState)
        }
    }
}

@Composable
fun GoalProgressBar(
    currentAmount: Long,
    targetAmount: Long,
    modifier: Modifier = Modifier
) {
    val progress = remember(currentAmount, targetAmount) {
        if (targetAmount <= 0L) 0f
        else {
            val ratio = currentAmount.toFloat() / targetAmount.toFloat()
            ratio.coerceIn(0f, 1f)
        }
    }

    val percentText = if (targetAmount <= 0L) {
        "0%"
    } else {
        "${(progress * 100).toInt()}%"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(50))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = percentText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// 홈 탭 상단에 노출되는 월 합계 카드
@Composable
fun SummaryCard(monthLabel: String, netTotal: Long) {
    val monthNumber = monthLabel.substring(5, 7).toInt()

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

// 기존 내역 수정/삭제 화면
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
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("수정할 내역을 찾을 수 없습니다.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onCancel) { Text("뒤로 가기") }
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

    var isRecurring by remember { mutableStateOf(transaction.isRecurring) }
    var recurringInterval by remember {
        mutableStateOf(transaction.recurringInterval ?: "monthly")
    }
    var hasEndDate by remember {
        mutableStateOf(transaction.recurringEndDate != null)
    }
    var endDateMillis by remember {
        mutableStateOf<Long?>(transaction.recurringEndDate?.toDate()?.time)
    }

    val formattedEndDate = remember(hasEndDate, endDateMillis) {
        if (!hasEndDate || endDateMillis == null) {
            "없음"
        } else {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(Date(endDateMillis!!))
        }
    }

    var showCategorySheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
    )

    var showEndDatePicker by remember { mutableStateOf(false) }
    val endDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = endDateMillis ?: System.currentTimeMillis()
    )

    val disabledLikeEnabledColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surface
    )

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
                                    Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT)
                                        .show()
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

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "결제 유형",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TypeToggleButton(
                    text = "일반",
                    selected = !isRecurring,
                    onClick = {
                        isRecurring = false
                        hasEndDate = false
                        endDateMillis = null
                    },
                    modifier = Modifier.weight(1f)
                )

                TypeToggleButton(
                    text = "정기 결제",
                    selected = isRecurring,
                    onClick = { isRecurring = true },
                    modifier = Modifier.weight(1f)
                )
            }

            if (isRecurring) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "반복 주기",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TypeToggleButton(
                        text = "주",
                        selected = recurringInterval == "weekly",
                        onClick = { recurringInterval = "weekly" },
                        modifier = Modifier.weight(1f)
                    )
                    TypeToggleButton(
                        text = "월",
                        selected = recurringInterval == "monthly",
                        onClick = { recurringInterval = "monthly" },
                        modifier = Modifier.weight(1f)
                    )
                    TypeToggleButton(
                        text = "년",
                        selected = recurringInterval == "yearly",
                        onClick = { recurringInterval = "yearly" },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("마감일")
                        Text(
                            text = "기본값은 '없음'이며, 설정한 경우에만 적용됩니다.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = hasEndDate,
                        onCheckedChange = { checked ->
                            hasEndDate = checked
                            if (!checked) {
                                endDateMillis = null
                            }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let {
                            if (hasEndDate) it.clickable { showEndDatePicker = true }
                            else it
                        }
                ) {
                    OutlinedTextField(
                        value = formattedEndDate,
                        onValueChange = {},
                        label = { Text("마감일") },
                        readOnly = true,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (hasEndDate) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null)
                            }
                        },
                        colors = disabledLikeEnabledColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val amount = amountText.toLongOrNull() ?: 0
                    if (amount <= 0) {
                        Toast.makeText(context, "유효한 금액이 아닙니다.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val ts = Timestamp(Date(selectedDateMillis ?: System.currentTimeMillis()))

                    val data = mutableMapOf<String, Any>(
                        "amount" to amount,
                        "memo" to memo,
                        "category" to selectedCategory,
                        "type" to type,
                        "date" to ts,
                        "isRecurring" to isRecurring
                    )

                    if (isRecurring) {
                        data["recurringInterval"] = recurringInterval
                        if (hasEndDate && endDateMillis != null) {
                            data["recurringEndDate"] = Timestamp(Date(endDateMillis!!))
                        }
                    }

                    db.collection("users")
                        .document(uid)
                        .collection("transactions")
                        .document(transaction.id)
                        .update(data)
                        .addOnSuccessListener {
                            Toast.makeText(context, "수정되었습니다.", Toast.LENGTH_SHORT).show()
                            onSaved()
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("저장하기")
            }
        }
    }

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

    if (showEndDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        endDatePickerState.selectedDateMillis?.let {
                            endDateMillis = it
                            hasEndDate = true
                        }
                        showEndDatePicker = false
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = endDatePickerState)
        }
    }
}

// 설정 화면: 다크 모드, 카테고리 관리, 앱 정보
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
                            if (checked) "다크 모드로 전환되었습니다." else "라이트 모드로 전환되었습니다.",
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
                        text = "카테고리를 추가 또는 삭제합니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "카테고리 관리로 이동"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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

// 카테고리 관리 화면
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

    // 카테고리 리스트 전체를 Firestore에 저장하는 헬퍼 함수
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
                    "카테고리를 저장하지 못했습니다: ${e.localizedMessage}",
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
                Text("로그인 상태에서만 카테고리를 관리할 수 있습니다.")
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
                            Toast.makeText(
                                context,
                                "카테고리 이름을 입력해 주세요.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        if (categories.any { it == trimmed }) {
                            Toast.makeText(
                                context,
                                "이미 존재하는 카테고리입니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }

                        val updated = categories + trimmed
                        categories = updated
                        newCategory = ""
                        saveCategories(updated)
                        Toast.makeText(
                            context,
                            "카테고리가 추가되었습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
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
                                        "카테고리가 삭제되었습니다.",
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

// 앱 정보 화면
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAppScreen(
    onBack: () -> Unit = {}
) {
    val versionName = "1.4.1"
    val versionCode = 10

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
            Text(
                text = "앱 버전",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "GoalPocket v$versionName (Build $versionCode)",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

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

            Text(
                text = "변경사항 (v$versionName)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• 목표기능이 추가되었습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(
    goal: GoalItem?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    if (goal == null || uid == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("목표 상세") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("목표 정보를 불러오는 중 문제가 발생했습니다.")
            }
        }
        return
    }

    var records by remember { mutableStateOf<List<GoalRecordItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var currentAmount by remember { mutableStateOf(goal.currentAmount) }

    var showAddDialog by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf("add") }  // "add" 또는 "withdraw"
    var addAmountText by remember { mutableStateOf("") }
    var addMemo by remember { mutableStateOf("") }
    var addDateMillis by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var showAddDatePicker by remember { mutableStateOf(false) }
    val addDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = addDateMillis ?: System.currentTimeMillis()
    )

    var editingRecord by remember { mutableStateOf<GoalRecordItem?>(null) }
    var editType by remember { mutableStateOf("add") }
    var editAmountText by remember { mutableStateOf("") }
    var editMemo by remember { mutableStateOf("") }
    var editDateMillis by remember { mutableStateOf<Long?>(null) }
    var showEditDatePicker by remember { mutableStateOf(false) }
    val editDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = editDateMillis ?: System.currentTimeMillis()
    )

    // 기록 실시간 구독 + currentAmount 재계산
    DisposableEffect(uid, goal.id) {
        isLoading = true

        val registration = db.collection("users")
            .document(uid)
            .collection("goals")
            .document(goal.id)
            .collection("records")
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    isLoading = false
                    Log.e("GoalDetailScreen", "records snapshot error", e)
                    Toast.makeText(
                        context,
                        "입출금 기록을 불러오는 중 오류가 발생했습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val list = snapshot.documents.map { doc ->
                        GoalRecordItem(
                            id = doc.id,
                            amount = doc.getLong("amount") ?: 0L,
                            memo = doc.getString("memo") ?: "",
                            type = doc.getString("type") ?: "add",
                            date = doc.getTimestamp("date")
                        )
                    }
                    records = list

                    val newAmount = list.sumOf { r ->
                        if (r.type == "withdraw") -r.amount else r.amount
                    }
                    currentAmount = newAmount

                    db.collection("users")
                        .document(uid)
                        .collection("goals")
                        .document(goal.id)
                        .update("currentAmount", newAmount)
                        .addOnFailureListener { ex ->
                            Log.e("GoalDetailScreen", "update currentAmount error", ex)
                        }

                    isLoading = false
                }
            }

        onDispose {
            registration.remove()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("목표 상세") },
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
            // 상단 요약 영역
            Text(
                text = goal.name,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            val deadlineText = formatDate(goal.deadline)
            Text(
                text = if (goal.deadline == null) "마감일 없음" else "마감일: $deadlineText",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (goal.category.isBlank()) "카테고리: 없음" else "카테고리: ${goal.category}",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "달성률",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            GoalProgressBar(
                currentAmount = currentAmount,
                targetAmount = goal.targetAmount
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "%,d원 / %,d원".format(currentAmount, goal.targetAmount),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            // 입출금 기록 헤더 + 추가 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "입출금 기록",
                    style = MaterialTheme.typography.titleMedium
                )
                Button(onClick = { showAddDialog = true }) {
                    Text("기록 추가")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                records.isEmpty() -> {
                    Text(
                        text = "등록된 입출금 기록이 없습니다. \"기록 추가\" 버튼을 눌러 첫 기록을 만들어 보세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(records) { record ->
                            val sign = if (record.type == "add") "+" else "-"
                            val color =
                                if (record.type == "add") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editingRecord = record
                                        editType = record.type
                                        editAmountText = record.amount.toString()
                                        editMemo = record.memo
                                        editDateMillis =
                                            record.date?.toDate()?.time ?: System.currentTimeMillis()
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = record.memo.ifBlank { "메모 없음" },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "$sign${"%,d".format(record.amount)}원",
                                        color = color
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = formatDate(record.date),
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

    // 기록 추가 다이얼로그
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = addAmountText.toLongOrNull() ?: 0L
                        if (amount <= 0) {
                            Toast.makeText(
                                context,
                                "금액은 0원보다 커야 합니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }

                        val dateTs = Timestamp(
                            Date(addDateMillis ?: System.currentTimeMillis())
                        )

                        val data = mapOf(
                            "amount" to amount,
                            "memo" to addMemo,
                            "type" to addType,
                            "date" to dateTs,
                            "goalName" to goal.name
                        )

                        db.collection("users")
                            .document(uid)
                            .collection("goals")
                            .document(goal.id)
                            .collection("records")
                            .add(data)
                            .addOnSuccessListener {
                                Toast.makeText(
                                    context,
                                    "기록이 추가되었습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                addAmountText = ""
                                addMemo = ""
                                addType = "add"
                                addDateMillis = System.currentTimeMillis()
                                showAddDialog = false
                            }
                            .addOnFailureListener { ex ->
                                Toast.makeText(
                                    context,
                                    "기록 추가 중 오류가 발생했습니다: ${ex.localizedMessage}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("취소")
                }
            },
            title = { Text("입출금 기록 추가") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TypeToggleButton(
                            text = "추가",
                            selected = addType == "add",
                            onClick = { addType = "add" },
                            modifier = Modifier.weight(1f)
                        )
                        TypeToggleButton(
                            text = "출금",
                            selected = addType == "withdraw",
                            onClick = { addType = "withdraw" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = addAmountText,
                        onValueChange = {
                            addAmountText = it.filter { ch -> ch.isDigit() }
                        },
                        label = { Text("금액 (원)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = addMemo,
                        onValueChange = { addMemo = it },
                        label = { Text("메모 (선택)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val formattedDate = remember(addDateMillis) {
                        val sdf = java.text.SimpleDateFormat(
                            "yyyy-MM-dd",
                            java.util.Locale.getDefault()
                        )
                        sdf.format(Date(addDateMillis ?: System.currentTimeMillis()))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddDatePicker = true }
                    ) {
                        OutlinedTextField(
                            value = formattedDate,
                            onValueChange = {},
                            label = { Text("날짜") },
                            readOnly = true,
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }
        )
    }

    // 기록 추가용 날짜 선택
    if (showAddDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showAddDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        addDatePickerState.selectedDateMillis?.let {
                            addDateMillis = it
                        }
                        showAddDatePicker = false
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDatePicker = false }) {
                    Text("취소")
                }
            }
        ) {
            DatePicker(state = addDatePickerState)
        }
    }

    // 기록 수정 다이얼로그
    if (editingRecord != null) {
        val record = editingRecord!!
        AlertDialog(
            onDismissRequest = { editingRecord = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = editAmountText.toLongOrNull() ?: 0L
                        if (amount <= 0) {
                            Toast.makeText(
                                context,
                                "금액은 0원보다 커야 합니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }

                        val dateTs = Timestamp(
                            Date(editDateMillis ?: System.currentTimeMillis())
                        )

                        val data = mapOf(
                            "amount" to amount,
                            "memo" to editMemo,
                            "type" to editType,
                            "date" to dateTs,
                            "goalName" to goal.name
                        )

                        db.collection("users")
                            .document(uid)
                            .collection("goals")
                            .document(goal.id)
                            .collection("records")
                            .document(record.id)
                            .update(data)
                            .addOnSuccessListener {
                                Toast.makeText(
                                    context,
                                    "기록이 수정되었습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                editingRecord = null
                            }
                            .addOnFailureListener { ex ->
                                Toast.makeText(
                                    context,
                                    "기록 수정 중 오류가 발생했습니다: ${ex.localizedMessage}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                Row {
                    // 이 기록(GoalRecordItem)만 삭제
                    TextButton(
                        onClick = {
                            db.collection("users")
                                .document(uid)
                                .collection("goals")
                                .document(goal.id)
                                .collection("records")
                                .document(record.id)
                                .delete()
                                .addOnSuccessListener {
                                    Toast.makeText(
                                        context,
                                        "기록이 삭제되었습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    editingRecord = null
                                }
                                .addOnFailureListener { ex ->
                                    Toast.makeText(
                                        context,
                                        "기록 삭제 중 오류가 발생했습니다: ${ex.localizedMessage}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                    ) {
                        Text(
                            text = "삭제",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    TextButton(onClick = { editingRecord = null }) {
                        Text("취소")
                    }
                }
            },
            title = { Text("입출금 기록 수정") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TypeToggleButton(
                            text = "추가",
                            selected = editType == "add",
                            onClick = { editType = "add" },
                            modifier = Modifier.weight(1f)
                        )
                        TypeToggleButton(
                            text = "출금",
                            selected = editType == "withdraw",
                            onClick = { editType = "withdraw" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editAmountText,
                        onValueChange = {
                            editAmountText = it.filter { ch -> ch.isDigit() }
                        },
                        label = { Text("금액 (원)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editMemo,
                        onValueChange = { editMemo = it },
                        label = { Text("메모 (선택)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val formattedEditDate = remember(editDateMillis) {
                        val sdf = java.text.SimpleDateFormat(
                            "yyyy-MM-dd",
                            java.util.Locale.getDefault()
                        )
                        sdf.format(Date(editDateMillis ?: System.currentTimeMillis()))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEditDatePicker = true }
                    ) {
                        OutlinedTextField(
                            value = formattedEditDate,
                            onValueChange = {},
                            label = { Text("날짜") },
                            readOnly = true,
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }
        )
    }

    // 기록 수정용 날짜 선택
    if (showEditDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEditDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        editDatePickerState.selectedDateMillis?.let {
                            editDateMillis = it
                        }
                        showEditDatePicker = false
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDatePicker = false }) {
                    Text("취소")
                }
            }
        ) {
            DatePicker(state = editDatePickerState)
        }
    }
}
