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
//TODO: 앱 잠금
//TODO: 숨김 카테고리

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
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import androidx.compose.foundation.border

// 날짜를 "yyyy-MM-dd" 문자열로 포맷팅하는 유틸 함수
fun formatDate(ts: Timestamp?): String {
    if (ts == null) return "-"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(ts.toDate())
}

// 수입/지출 타입에 따라 부호를 붙여서 금액을 문자열로 만들어주는 유틸 함수
// ex) income, 10000 -> "+10,000원", expense, 5000 -> "-5,000원"
fun formatSignedAmount(amount: Long, type: String): String {
    val sign = if (type == "income") "+" else "-"
    return "$sign${"%,d".format(amount)}원"
}

// 순이익(수입 - 지출)을 표시할 때 사용하는 포맷 함수
// 양수면 "+"를, 음수면 "-"를 붙이고, 0이면 부호 없이 "0원"만 보여준다
fun formatNetAmount(net: Long): String {
    val sign = when {
        net > 0 -> "+"
        net < 0 -> "-"
        else -> ""
    }
    return "$sign${"%,d".format(abs(net))}원"
}

// 앱이 최초 제공하는 기본 카테고리 목록
// 사용자가 설정 화면에서 커스터마이징하면 Firestore에 저장된 값으로 대체된다
fun defaultCategories(): List<String> =
    listOf("식비", "카페", "교통", "쇼핑", "기타")

// 주어진 원본 트랜잭션 리스트를 바탕으로,
// 특정 연/월에 표시할 내역(정기 결제의 가상 내역 포함)을 만들어준다.
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
    val monthEnd = monthEndCal.timeInMillis // [monthStart, monthEnd) 범위

    for (tx in baseTransactions) {
        val baseDate = tx.date?.toDate() ?: continue
        val baseMillis = baseDate.time
        val endMillis = tx.recurringEndDate?.toDate()?.time

        // 일반 내역은 해당 달에 들어가면 그대로 추가
        if (!tx.isRecurring) {
            if (baseMillis in monthStart until monthEnd) {
                result.add(tx)
            }
            continue
        }

        when (tx.recurringInterval) {
            "monthly" -> {
                val baseCal = Calendar.getInstance().apply { time = baseDate }
                val baseYear = baseCal.get(Calendar.YEAR)
                val baseMonth = baseCal.get(Calendar.MONTH)

                // 시작 이전 달이면 스킵
                if (year < baseYear || (year == baseYear && month < baseMonth)) {
                    continue
                }

                val occCal = Calendar.getInstance().apply {
                    set(
                        year,
                        month,
                        1,
                        baseCal.get(Calendar.HOUR_OF_DAY),
                        baseCal.get(Calendar.MINUTE),
                        baseCal.get(Calendar.SECOND)
                    )
                    set(Calendar.MILLISECOND, baseCal.get(Calendar.MILLISECOND))

                    val day = baseCal.get(Calendar.DAY_OF_MONTH)
                    val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                    set(Calendar.DAY_OF_MONTH, minOf(day, maxDay))
                }

                val occMillis = occCal.timeInMillis
                if (occMillis < baseMillis) continue
                if (endMillis != null && occMillis > endMillis) continue
                if (occMillis !in monthStart until monthEnd) continue

                result.add(
                    tx.copy(
                        occurrenceDate = Timestamp(Date(occMillis))
                    )
                )
            }

            "yearly" -> {
                val baseCal = Calendar.getInstance().apply { time = baseDate }
                val baseYear = baseCal.get(Calendar.YEAR)
                val baseMonth = baseCal.get(Calendar.MONTH)

                // 연/월이 맞지 않으면 스킵
                if (month != baseMonth || year < baseYear) continue

                val occCal = Calendar.getInstance().apply {
                    set(
                        year,
                        month,
                        baseCal.get(Calendar.DAY_OF_MONTH),
                        baseCal.get(Calendar.HOUR_OF_DAY),
                        baseCal.get(Calendar.MINUTE),
                        baseCal.get(Calendar.SECOND)
                    )
                    set(Calendar.MILLISECOND, baseCal.get(Calendar.MILLISECOND))

                    val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                    if (get(Calendar.DAY_OF_MONTH) > maxDay) {
                        set(Calendar.DAY_OF_MONTH, maxDay)
                    }
                }

                val occMillis = occCal.timeInMillis
                if (occMillis < baseMillis) continue
                if (endMillis != null && occMillis > endMillis) continue
                if (occMillis !in monthStart until monthEnd) continue

                result.add(
                    tx.copy(
                        occurrenceDate = Timestamp(Date(occMillis))
                    )
                )
            }

            "weekly" -> {
                val baseCal = Calendar.getInstance().apply { time = baseDate }

                val occCal = Calendar.getInstance().apply {
                    timeInMillis = maxOf(baseMillis, monthStart)
                }

                // 먼저 같은 요일에 맞춰 이동
                while (occCal.timeInMillis < monthEnd &&
                    occCal.get(Calendar.DAY_OF_WEEK) != baseCal.get(Calendar.DAY_OF_WEEK)
                ) {
                    occCal.add(Calendar.DAY_OF_MONTH, 1)
                }

                // 해당 달 범위 내에서 7일씩 증가시키면서 발생
                while (occCal.timeInMillis in monthStart until monthEnd) {
                    val occMillis = occCal.timeInMillis
                    if (occMillis >= baseMillis &&
                        (endMillis == null || occMillis <= endMillis)
                    ) {
                        result.add(
                            tx.copy(
                                occurrenceDate = Timestamp(Date(occMillis))
                            )
                        )
                    }
                    occCal.add(Calendar.DAY_OF_MONTH, 7)
                }
            }

            else -> {
                // 정의되지 않은 interval은 그냥 원본 날짜 기준 단일 발생만
                if (baseMillis in monthStart until monthEnd) {
                    result.add(tx)
                }
            }
        }
    }

    // 화면용이니까 날짜(occurrenceDate 우선) 기준 내림차순 정렬
    return result.sortedByDescending {
        (it.occurrenceDate ?: it.date)?.toDate()?.time ?: 0L
    }
}


// 개별 가계부 내역(트랜잭션)을 표현하는 데이터 모델
data class TransactionItem(
    val id: String,          // Firestore document ID
    val amount: Long,        // 금액
    val memo: String,        // 메모
    val type: String,        // "income" 또는 "expense"
    val category: String,    // 카테고리 이름
    val date: Timestamp?,    // 시작일 (원본 기준)
    val isRecurring: Boolean = false,
    val recurringInterval: String? = null,      // "weekly", "monthly", "yearly"
    val recurringEndDate: Timestamp? = null,    // 반복 종료일(옵션)
    val occurrenceDate: Timestamp? = null       // 실제 발생일(가상 내역용)
)

// 카테고리별 합계를 표현하는 데이터 모델
data class CategoryTotal(
    val category: String,    // 카테고리 이름
    val total: Long          // 해당 카테고리의 합계 (수입 +, 지출 -)
)

// 앱 진입 지점. Compose를 사용해 전체 화면 구조와 네비게이션을 구성한다.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase SDK 초기화 (앱 전체에서 한 번만 설정)
        FirebaseApp.initializeApp(this)
        Log.d(
            "FirebaseTest",
            "Firebase initialized: ${FirebaseApp.getApps(this).isNotEmpty()}"
        )

        setContent {
            // 다크모드 여부를 저장하는 상태. rememberSaveable 덕분에 프로세스 내 재구성에도 유지된다.
            var isDarkMode by rememberSaveable { mutableStateOf(false) }

            // 앱 전체 테마 적용
            GoalPocketTheme(darkTheme = isDarkMode) {

                // 간단한 문자열 기반 화면 상태. 실제 앱에서는 NavHost로 대체 가능.
                var screen by remember { mutableStateOf("login") }

                // 수정 화면으로 전달할 선택된 트랜잭션
                var selectedTransaction by remember { mutableStateOf<TransactionItem?>(null) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 화면 스위칭 로직
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
                            onOpenCalendar = { screen = "calendar" }, // 현재는 bottom 탭으로 처리
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

// 로그인 화면. Firebase Auth를 이용해 이메일/비밀번호 로그인 처리.
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

        // 이메일 입력
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("이메일") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 비밀번호 입력 (비가시 처리)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("비밀번호") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 로그인 버튼
        Button(
            onClick = {
                if (isLoading) return@Button

                if (email.isBlank() || password.isBlank()) {
                    Toast.makeText(context, "이메일과 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true

                // Firebase 이메일/비밀번호 로그인 요청
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

        // 회원가입 화면으로 이동
        TextButton(onClick = onSignUpClick) {
            Text("회원가입")
        }
    }
}

// 회원가입 화면. Firebase Auth를 통해 계정을 생성한다.
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

        // 이메일 입력
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("이메일") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 비밀번호 입력
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("비밀번호") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 비밀번호 확인 입력
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("비밀번호 확인") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 회원가입 처리 버튼
        Button(
            onClick = {
                if (isLoading) return@Button

                // 유효성 검증
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

                // Firebase 계정 생성 요청
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

        // 로그인 화면으로 복귀
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
    onOpenCalendar: () -> Unit = {},   // 현재는 사용하지 않지만 구조상 남겨둠
    onOpenSettings: () -> Unit = {},
    onSelectTransaction: (TransactionItem) -> Unit = {}
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    // Firestore에서 가져온 전체 내역 목록
    var transactions by remember { mutableStateOf<List<TransactionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // 현재 연/월을 기준으로 시작
    val now = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) } // 0~11

    // 하단 네비게이션 탭 인덱스 (0: 홈, 1: 카테고리, 2: 캘린더)
    var selectedTab by remember { mutableStateOf(0) }

    // Firestore 실시간 구독. uid가 바뀌면 새로 구독하고, Composable이 dispose될 때 구독 해제.
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
                                "내역 불러오기 실패: ${e.localizedMessage}",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@addSnapshotListener
                        }

                        if (snapshot != null) {
                            // 문서를 TransactionItem 리스트로 매핑
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

            // DisposableEffect가 종료될 때 리스너 해제
            onDispose {
                registration.remove()
            }
        }
    }

    // 현재 선택된 연/월에 대해 정기결제를 포함한 가상 내역 리스트 생성
    val filteredTransactions = remember(transactions, selectedYear, selectedMonth) {
        expandTransactionsForMonth(
            baseTransactions = transactions,
            year = selectedYear,
            month = selectedMonth
        )
    }

    // 월별 수입, 지출, 순이익 계산
    val incomeTotal = filteredTransactions
        .filter { it.type == "income" }
        .sumOf { it.amount }

    val expenseTotal = filteredTransactions
        .filter { it.type != "income" }
        .sumOf { it.amount }

    val netTotal = incomeTotal - expenseTotal
    val monthLabel = "%04d-%02d".format(selectedYear, selectedMonth + 1)

    // 카테고리별 합계 계산 (수입/지출 모두 포함, 수입 +, 지출 -)
    val categoryTotals = remember(filteredTransactions) {
        filteredTransactions
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GoalPocket") },
                actions = {
                    // 설정 화면 이동
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                    // 로그아웃
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        },
        bottomBar = {
            // 하단 네비게이션 바: 홈 / 카테고리 / 캘린더
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

            // 상단의 월 이동 UI (◀ 2025-12 ▶)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        // 이전 달로 이동. 1월에서 이전이면 전년도 12월로.
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
                        // 다음 달로 이동. 12월에서 다음이면 다음 해 1월로.
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

            // 탭별 화면 전환
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
                    monthTransactions = filteredTransactions,
                    onSelectTransaction = onSelectTransaction   // 날짜별 내역 클릭 시 수정으로 연결
                )
            }
        }
    }
}

// 홈 탭 메인 내용. 요약 카드 + 내역 리스트를 보여준다.
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

        // 월별 순이익 요약 카드
        SummaryCard(monthLabel = monthLabel, netTotal = netTotal)

        Spacer(modifier = Modifier.height(12.dp))

        // 내역 추가 버튼
        Button(
            onClick = onAddTransaction,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("내역 추가")
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 상세 내역 제목
        Text(
            text = "상세 내역",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            filteredTransactions.isEmpty() -> {
                Text("이 달 등록된 내역이 없습니다.")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredTransactions) { tx ->
                        // 화면에 표시할 날짜는 occurrenceDate 우선, 없으면 원래 date
                        val displayDate = tx.occurrenceDate ?: tx.date

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

                            // 두 번째 줄: 카테고리(+ 정기결제 뱃지) + 날짜
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
            }
        }
    }
}

// 카테고리 탭. 카테고리별 합계(수입/지출 모두 반영)를 보여준다.
@Composable
fun HomeCategoryTab(
    categoryTotals: List<CategoryTotal>,
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

    if (categoryTotals.isEmpty()) {
        Text("이 달 내역이 없습니다.")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        categoryTotals.forEach { ct ->
            val amountText = formatNetAmount(ct.total)
            val amountColor = when {
                ct.total > 0 -> MaterialTheme.colorScheme.primary   // 순수입
                ct.total < 0 -> MaterialTheme.colorScheme.error     // 순지출
                else -> MaterialTheme.colorScheme.onSurface         // 0원
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
            Divider()
        }
    }
}

// 캘린더 탭. 일별 순이익을 간단한 캘린더 형태로 보여주고,
// 날짜를 선택하면 해당 날짜의 내역 리스트를 제공한다.
// 캘린더 탭. 일별 순이익을 간단한 캘린더 형태로 보여주고,
// 날짜를 선택하면 해당 날짜의 내역 리스트를 제공한다.
// 캘린더 탭. 일별 순이익을 간단한 캘린더 형태로 보여주고,
// 날짜를 선택하면 해당 날짜의 내역 리스트를 제공한다.
@Composable
fun HomeCalendarTab(
    year: Int,
    month: Int, // 0~11
    monthLabel: String,
    monthTransactions: List<TransactionItem>,
    onSelectTransaction: (TransactionItem) -> Unit
) {
    // 날짜별 합계 맵 생성. key: 일(dayOfMonth), value: 순이익(수입 - 지출)
    // 정기결제 가상 내역은 occurrenceDate를 우선 사용.
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

    // 선택된 일자에 해당하는 내역만 필터링 (역시 occurrenceDate 우선)
    val selectedDayTransactions = remember(monthTransactions, selectedDay) {
        if (selectedDay == null) emptyList()
        else monthTransactions.filter { tx ->
            val ts = (tx.occurrenceDate ?: tx.date) ?: return@filter false
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

    // 요일 헤더 표시
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

    // 해당 연/월의 1일 기준으로 달력 구조 계산
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
            "날짜를 선택하세요."
        else
            "${monthLabel}-${"%02d".format(selectedDay)} 내역",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    when {
        selectedDay != null && selectedDayTransactions.isEmpty() -> {
            Text("이 날짜에는 등록된 내역이 없습니다.")
        }

        selectedDayTransactions.isNotEmpty() -> {
            LazyColumn {
                items(selectedDayTransactions) { tx ->
                    val amountText = formatSignedAmount(tx.amount, tx.type)
                    val amountColor =
                        if (tx.type == "income") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTransaction(tx) }   // 메인 내역 수정으로 이동
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

                        // 카테고리 + 정기결제 뱃지
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
        }
    }
}



// 수입/지출 타입을 선택하는 토글 버튼. 지출/수입 두 버튼에서 재사용된다.
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

    // 정기결제 관련 상태
    var isRecurring by remember { mutableStateOf(false) }                 // 일반/정기결제 토글
    var recurringInterval by remember { mutableStateOf("monthly") }      // "weekly", "monthly", "yearly"
    var hasEndDate by remember { mutableStateOf(false) }                 // 마감일 사용 여부
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

    // 정기결제 마감일용 DatePicker
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

        // 지출/수입 타입 토글
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

        // 카테고리 선택
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

        // 거래 날짜 선택
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

        // 일반 / 정기결제 토글
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

            // 주기 선택 (주 / 월 / 년)
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

            // 마감일 설정
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
                        text = "기본은 '없음'이고, 설정한 경우에만 적용돼.",
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
                    Toast.makeText(context, "로그인 정보 없음", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (amountText.isBlank()) {
                    Toast.makeText(context, "금액 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val amount = amountText.toLongOrNull() ?: 0
                if (amount <= 0) {
                    Toast.makeText(context, "금액이 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
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

    // 거래 날짜 선택
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

    // 정기결제 마감일 선택
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

// 월 합계(순이익)를 보여주는 카드. 홈 탭 상단에서 사용된다.
@Composable
fun SummaryCard(monthLabel: String, netTotal: Long) {
    val monthNumber = monthLabel.substring(5, 7).toInt()  // ex) "2025-12" → 12

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
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
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

    // 정기결제 관련 상태 (추가 화면과 동일한 구조)
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
            // 지출/수입 타입 토글
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

            // 카테고리 수정
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

            // 날짜 수정
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

            // 결제 유형 (일반 / 정기결제)
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
                            text = "기본은 '없음'이고, 설정한 경우에만 적용돼.",
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
                        Toast.makeText(context, "금액이 올바르지 않아", Toast.LENGTH_SHORT).show()
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

    // 설정 화면: 다크 모드 토글, 카테고리 관리, 앱 정보 진입 등을 제공
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
            // 다크 모드 스위치
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

            // 카테고리 관리 화면으로 이동
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

    // 카테고리 목록과 입력 상태
    var categories by remember { mutableStateOf(defaultCategories()) }
    var newCategory by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // 로그인된 사용자 기준으로 카테고리 설정 문서를 로드
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

                // 문서가 없거나 비어있으면 기본 카테고리로 초기화해서 저장
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

    // Firestore에 카테고리 리스트 전체를 저장하는 헬퍼
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
                // 비로그인 상태에서 접근 시 안내 문구
                Text("로그인 상태에서만 카테고리를 관리할 수 있어.")
                return@Column
            }

            Text(
                text = "카테고리 추가",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 상단의 카테고리 추가 영역
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

                        // 새 카테고리를 리스트에 추가하고 Firestore에 저장
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
                // 카테고리 로딩 중
                CircularProgressIndicator()
            } else {
                // 카테고리 리스트 및 삭제 버튼
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
    // 버전 정보는 직접 관리 (실제 앱에서는 BuildConfig에서 가져올 수 있음)
    val versionName = "1.2.1"
    val versionCode = 7

    // 앱 정보 화면. 버전, 개발자 정보, 변경사항 등을 안내한다.
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
                text = "GoalPocket v$versionName (Build $versionCode)",
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

            // 3. 최신 버전 변경사항
            Text(
                text = "변경사항 (v$versionName)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• 이제 캘린터 탭에서도 내역을 수정하고 삭제할 수 있습니다.\n" +
                        "• 카테고리 탭과 캘린터 탭의 금액 색상 표기가 수정되었습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
