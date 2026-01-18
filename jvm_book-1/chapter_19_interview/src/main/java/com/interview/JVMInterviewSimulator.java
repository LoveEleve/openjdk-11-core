package com.interview;

import java.util.*;
import java.util.concurrent.*;
import java.lang.management.*;

/**
 * JVM专家面试模拟器
 * 
 * 这个类提供了一套完整的JVM面试题目和评分系统
 * 可以用于技术面试的准备和候选人能力评估
 */
public class JVMInterviewSimulator {
    
    /**
     * 面试问题数据库
     */
    public static class InterviewQuestionBank {
        
        private final Map<InterviewLevel, List<InterviewQuestion>> questionsByLevel;
        
        public InterviewQuestionBank() {
            this.questionsByLevel = new EnumMap<>(InterviewLevel.class);
            initializeQuestions();
        }
        
        private void initializeQuestions() {
            // Level 1: 基础理论问题
            List<InterviewQuestion> basicQuestions = Arrays.asList(
                new InterviewQuestion(
                    "JVM内存结构",
                    "请详细描述JVM内存结构，包括各个区域的作用和特点",
                    Arrays.asList(
                        "堆内存：存储对象实例，分为年轻代和老年代",
                        "方法区：存储类信息、常量池、静态变量",
                        "虚拟机栈：存储局部变量、操作数栈、方法出口",
                        "程序计数器：记录当前执行的字节码指令地址",
                        "本地方法栈：为native方法服务"
                    ),
                    10,
                    InterviewLevel.BASIC
                ),
                
                new InterviewQuestion(
                    "垃圾回收算法",
                    "比较标记-清除、标记-整理、复制算法的优缺点",
                    Arrays.asList(
                        "标记-清除：简单但产生碎片",
                        "标记-整理：无碎片但移动成本高",
                        "复制算法：无碎片、速度快但空间利用率低",
                        "适用场景分析"
                    ),
                    10,
                    InterviewLevel.BASIC
                ),
                
                new InterviewQuestion(
                    "类加载机制",
                    "解释双亲委派模型的工作原理和优势",
                    Arrays.asList(
                        "委派流程：子类加载器先委派给父类加载器",
                        "安全性：防止核心类被篡改",
                        "唯一性：保证类的唯一性",
                        "打破双亲委派的场景"
                    ),
                    10,
                    InterviewLevel.BASIC
                )
            );
            
            // Level 2: 深度技术问题
            List<InterviewQuestion> advancedQuestions = Arrays.asList(
                new InterviewQuestion(
                    "G1 GC深度分析",
                    "在8GB堆内存环境下，分析G1 GC的Region管理机制",
                    Arrays.asList(
                        "Region大小计算：8GB/2048=4MB",
                        "Region类型：Eden、Survivor、Old、Humongous",
                        "Remember Set的作用和实现",
                        "混合回收的Collection Set选择策略",
                        "并发标记的SATB算法"
                    ),
                    15,
                    InterviewLevel.ADVANCED
                ),
                
                new InterviewQuestion(
                    "JIT编译优化",
                    "详细解释方法内联优化的实现原理",
                    Arrays.asList(
                        "热点检测机制",
                        "内联决策算法",
                        "内联失败的场景",
                        "去优化机制",
                        "性能影响分析"
                    ),
                    15,
                    InterviewLevel.ADVANCED
                ),
                
                new InterviewQuestion(
                    "内存分配机制",
                    "解释TLAB的工作原理和性能优势",
                    Arrays.asList(
                        "线程本地分配缓冲区概念",
                        "避免同步开销的机制",
                        "大小动态调整算法",
                        "慢路径分配处理",
                        "与不同GC的配合"
                    ),
                    15,
                    InterviewLevel.ADVANCED
                )
            );
            
            // Level 3: 实战场景问题
            List<InterviewQuestion> practicalQuestions = Arrays.asList(
                new InterviewQuestion(
                    "性能故障诊断",
                    "生产环境出现频繁Full GC，请描述完整的排查方案",
                    Arrays.asList(
                        "问题现象收集和分析",
                        "GC日志分析方法",
                        "Heap Dump分析技巧",
                        "根因定位策略",
                        "解决方案设计和实施"
                    ),
                    20,
                    InterviewLevel.PRACTICAL
                ),
                
                new InterviewQuestion(
                    "GC调优实战",
                    "为高并发交易系统设计GC调优方案",
                    Arrays.asList(
                        "业务特点分析",
                        "性能目标设定",
                        "GC算法选择",
                        "参数调优策略",
                        "监控和验证方案"
                    ),
                    20,
                    InterviewLevel.PRACTICAL
                )
            );
            
            // Level 4: 架构设计问题
            List<InterviewQuestion> architecturalQuestions = Arrays.asList(
                new InterviewQuestion(
                    "大规模系统设计",
                    "设计支持亿级用户的系统，JVM层面如何考虑？",
                    Arrays.asList(
                        "技术选型策略",
                        "性能优化方案",
                        "监控体系设计",
                        "可扩展性考虑",
                        "容器化适配"
                    ),
                    25,
                    InterviewLevel.ARCHITECTURAL
                )
            );
            
            questionsByLevel.put(InterviewLevel.BASIC, basicQuestions);
            questionsByLevel.put(InterviewLevel.ADVANCED, advancedQuestions);
            questionsByLevel.put(InterviewLevel.PRACTICAL, practicalQuestions);
            questionsByLevel.put(InterviewLevel.ARCHITECTURAL, architecturalQuestions);
        }
        
        public List<InterviewQuestion> getQuestionsByLevel(InterviewLevel level) {
            return questionsByLevel.getOrDefault(level, new ArrayList<>());
        }
        
        public InterviewQuestion getRandomQuestion(InterviewLevel level) {
            List<InterviewQuestion> questions = getQuestionsByLevel(level);
            if (questions.isEmpty()) {
                return null;
            }
            Random random = new Random();
            return questions.get(random.nextInt(questions.size()));
        }
    }
    
    /**
     * 面试问题
     */
    public static class InterviewQuestion {
        private final String category;
        private final String question;
        private final List<String> keyPoints;
        private final int maxScore;
        private final InterviewLevel level;
        
        public InterviewQuestion(String category, String question, List<String> keyPoints, 
                               int maxScore, InterviewLevel level) {
            this.category = category;
            this.question = question;
            this.keyPoints = new ArrayList<>(keyPoints);
            this.maxScore = maxScore;
            this.level = level;
        }
        
        public void displayQuestion() {
            System.out.println("=== " + category + " ===");
            System.out.println("问题: " + question);
            System.out.println("难度: " + level);
            System.out.println("满分: " + maxScore + "分");
            System.out.println();
        }
        
        public void displayAnswer() {
            System.out.println("参考答案要点:");
            for (int i = 0; i < keyPoints.size(); i++) {
                System.out.println((i + 1) + ". " + keyPoints.get(i));
            }
            System.out.println();
        }
        
        // getter方法
        public String getCategory() { return category; }
        public String getQuestion() { return question; }
        public List<String> getKeyPoints() { return keyPoints; }
        public int getMaxScore() { return maxScore; }
        public InterviewLevel getLevel() { return level; }
    }
    
    /**
     * 面试等级
     */
    public enum InterviewLevel {
        BASIC("基础理论"),
        ADVANCED("深度技术"),
        PRACTICAL("实战场景"),
        ARCHITECTURAL("架构设计");
        
        private final String description;
        
        InterviewLevel(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 面试会话
     */
    public static class InterviewSession {
        private final String candidateName;
        private final List<InterviewQuestion> questions;
        private final Map<InterviewQuestion, Integer> scores;
        private final long startTime;
        private InterviewLevel currentLevel;
        
        public InterviewSession(String candidateName) {
            this.candidateName = candidateName;
            this.questions = new ArrayList<>();
            this.scores = new HashMap<>();
            this.startTime = System.currentTimeMillis();
            this.currentLevel = InterviewLevel.BASIC;
        }
        
        public void addQuestion(InterviewQuestion question, int score) {
            questions.add(question);
            scores.put(question, score);
        }
        
        public InterviewResult generateResult() {
            long duration = System.currentTimeMillis() - startTime;
            return new InterviewResult(candidateName, questions, scores, duration);
        }
        
        public InterviewLevel getCurrentLevel() { return currentLevel; }
        public void setCurrentLevel(InterviewLevel level) { this.currentLevel = level; }
    }
    
    /**
     * 面试结果
     */
    public static class InterviewResult {
        private final String candidateName;
        private final List<InterviewQuestion> questions;
        private final Map<InterviewQuestion, Integer> scores;
        private final long durationMs;
        
        public InterviewResult(String candidateName, List<InterviewQuestion> questions,
                             Map<InterviewQuestion, Integer> scores, long durationMs) {
            this.candidateName = candidateName;
            this.questions = new ArrayList<>(questions);
            this.scores = new HashMap<>(scores);
            this.durationMs = durationMs;
        }
        
        public int getTotalScore() {
            return scores.values().stream().mapToInt(Integer::intValue).sum();
        }
        
        public int getMaxPossibleScore() {
            return questions.stream().mapToInt(InterviewQuestion::getMaxScore).sum();
        }
        
        public double getScorePercentage() {
            int maxScore = getMaxPossibleScore();
            return maxScore > 0 ? (double) getTotalScore() / maxScore * 100 : 0;
        }
        
        public InterviewGrade getGrade() {
            double percentage = getScorePercentage();
            if (percentage >= 90) return InterviewGrade.EXPERT;
            if (percentage >= 75) return InterviewGrade.SENIOR;
            if (percentage >= 60) return InterviewGrade.INTERMEDIATE;
            return InterviewGrade.JUNIOR;
        }
        
        public void printDetailedReport() {
            System.out.println("=== JVM专家面试结果报告 ===");
            System.out.println("候选人: " + candidateName);
            System.out.println("面试时长: " + (durationMs / 1000 / 60) + " 分钟");
            System.out.println("总分: " + getTotalScore() + "/" + getMaxPossibleScore());
            System.out.println("得分率: " + String.format("%.1f%%", getScorePercentage()));
            System.out.println("评级: " + getGrade().getDescription());
            
            System.out.println("\n=== 各题得分详情 ===");
            for (InterviewQuestion question : questions) {
                int score = scores.getOrDefault(question, 0);
                System.out.println(question.getCategory() + ": " + score + "/" + question.getMaxScore() + 
                                 " (" + question.getLevel().getDescription() + ")");
            }
            
            System.out.println("\n=== 能力评估 ===");
            printCapabilityAssessment();
            
            System.out.println("\n=== 改进建议 ===");
            printImprovementSuggestions();
        }
        
        private void printCapabilityAssessment() {
            Map<InterviewLevel, List<Integer>> scoresByLevel = new HashMap<>();
            
            for (InterviewQuestion question : questions) {
                InterviewLevel level = question.getLevel();
                int score = scores.getOrDefault(question, 0);
                scoresByLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(score);
            }
            
            for (InterviewLevel level : InterviewLevel.values()) {
                List<Integer> levelScores = scoresByLevel.get(level);
                if (levelScores != null && !levelScores.isEmpty()) {
                    double avgScore = levelScores.stream().mapToInt(Integer::intValue).average().orElse(0);
                    System.out.println(level.getDescription() + ": " + String.format("%.1f", avgScore) + "分 " +
                                     getCapabilityLevel(avgScore));
                }
            }
        }
        
        private String getCapabilityLevel(double avgScore) {
            if (avgScore >= 18) return "(优秀)";
            if (avgScore >= 15) return "(良好)";
            if (avgScore >= 12) return "(一般)";
            return "(需提升)";
        }
        
        private void printImprovementSuggestions() {
            double percentage = getScorePercentage();
            
            if (percentage >= 90) {
                System.out.println("• 技术能力优秀，建议关注前沿技术发展");
                System.out.println("• 可以考虑技术分享和团队培养");
            } else if (percentage >= 75) {
                System.out.println("• 基础扎实，建议加强实战经验积累");
                System.out.println("• 关注大规模系统的架构设计");
            } else if (percentage >= 60) {
                System.out.println("• 需要加强JVM理论学习");
                System.out.println("• 多参与实际项目的性能调优");
            } else {
                System.out.println("• 建议系统学习JVM基础知识");
                System.out.println("• 通过实际项目积累经验");
            }
        }
    }
    
    /**
     * 面试评级
     */
    public enum InterviewGrade {
        EXPERT("专家级", "深度理解JVM内部机制，有丰富的大规模生产环境经验"),
        SENIOR("高级", "熟练掌握JVM核心技术，有实际的性能调优经验"),
        INTERMEDIATE("中级", "掌握JVM基础知识，有一定的实战经验"),
        JUNIOR("初级", "基础知识需要加强，缺乏实际项目经验");
        
        private final String level;
        private final String description;
        
        InterviewGrade(String level, String description) {
            this.level = level;
            this.description = description;
        }
        
        public String getLevel() { return level; }
        public String getDescription() { return description; }
    }
    
    /**
     * 面试模拟器
     */
    public static class InterviewSimulator {
        private final InterviewQuestionBank questionBank;
        private final Scanner scanner;
        
        public InterviewSimulator() {
            this.questionBank = new InterviewQuestionBank();
            this.scanner = new Scanner(System.in);
        }
        
        /**
         * 开始面试模拟
         */
        public void startInterview() {
            System.out.println("=== JVM专家技术面试模拟器 ===");
            System.out.print("请输入候选人姓名: ");
            String candidateName = scanner.nextLine();
            
            InterviewSession session = new InterviewSession(candidateName);
            
            System.out.println("\n面试开始！");
            System.out.println("面试分为4个层次，每个层次会根据回答质量决定是否继续");
            
            // Level 1: 基础理论
            if (conductLevelInterview(session, InterviewLevel.BASIC)) {
                // Level 2: 深度技术
                if (conductLevelInterview(session, InterviewLevel.ADVANCED)) {
                    // Level 3: 实战场景
                    if (conductLevelInterview(session, InterviewLevel.PRACTICAL)) {
                        // Level 4: 架构设计
                        conductLevelInterview(session, InterviewLevel.ARCHITECTURAL);
                    }
                }
            }
            
            // 生成面试结果
            InterviewResult result = session.generateResult();
            result.printDetailedReport();
        }
        
        private boolean conductLevelInterview(InterviewSession session, InterviewLevel level) {
            System.out.println("\n=== " + level.getDescription() + "面试 ===");
            
            List<InterviewQuestion> questions = questionBank.getQuestionsByLevel(level);
            int passedQuestions = 0;
            int totalQuestions = Math.min(2, questions.size()); // 每个级别最多2题
            
            for (int i = 0; i < totalQuestions; i++) {
                InterviewQuestion question = questions.get(i);
                question.displayQuestion();
                
                System.out.println("请开始回答 (输入'next'查看参考答案): ");
                String answer = scanner.nextLine();
                
                if ("next".equalsIgnoreCase(answer)) {
                    question.displayAnswer();
                }
                
                System.out.print("请为这个回答打分 (0-" + question.getMaxScore() + "): ");
                int score = 0;
                try {
                    score = Integer.parseInt(scanner.nextLine());
                    score = Math.max(0, Math.min(score, question.getMaxScore()));
                } catch (NumberFormatException e) {
                    System.out.println("输入无效，默认为0分");
                }
                
                session.addQuestion(question, score);
                
                // 判断是否通过该题
                if (score >= question.getMaxScore() * 0.6) { // 60%为通过线
                    passedQuestions++;
                }
                
                System.out.println("得分: " + score + "/" + question.getMaxScore());
            }
            
            // 判断是否通过该级别
            boolean passed = passedQuestions >= totalQuestions * 0.5; // 50%通过率
            
            if (passed) {
                System.out.println("✅ " + level.getDescription() + "面试通过，继续下一级别");
            } else {
                System.out.println("❌ " + level.getDescription() + "面试未通过，面试结束");
            }
            
            return passed;
        }
    }
    
    /**
     * 实际JVM性能测试工具
     */
    public static class JVMPerformanceTester {
        
        /**
         * 获取当前JVM运行时信息
         */
        public static void printJVMRuntimeInfo() {
            System.out.println("=== 当前JVM运行时信息 ===");
            
            // 基本信息
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            System.out.println("JVM名称: " + runtime.getVmName());
            System.out.println("JVM版本: " + runtime.getVmVersion());
            System.out.println("运行时间: " + (runtime.getUptime() / 1000) + " 秒");
            
            // 内存信息
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memory.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memory.getNonHeapMemoryUsage();
            
            System.out.println("\n堆内存使用:");
            System.out.println("  已用: " + (heapUsage.getUsed() / 1024 / 1024) + "MB");
            System.out.println("  最大: " + (heapUsage.getMax() / 1024 / 1024) + "MB");
            System.out.println("  使用率: " + String.format("%.2f%%", 
                             (double) heapUsage.getUsed() / heapUsage.getMax() * 100));
            
            System.out.println("\n非堆内存使用:");
            System.out.println("  已用: " + (nonHeapUsage.getUsed() / 1024 / 1024) + "MB");
            System.out.println("  最大: " + (nonHeapUsage.getMax() / 1024 / 1024) + "MB");
            
            // GC信息
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            System.out.println("\nGC统计信息:");
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                System.out.println("  " + gcBean.getName() + ":");
                System.out.println("    GC次数: " + gcBean.getCollectionCount());
                System.out.println("    GC时间: " + gcBean.getCollectionTime() + "ms");
            }
        }
        
        /**
         * 内存分配性能测试
         */
        public static void testAllocationPerformance() {
            System.out.println("\n=== 内存分配性能测试 ===");
            
            int objectCount = 1000000;
            long startTime = System.nanoTime();
            
            List<Object> objects = new ArrayList<>();
            for (int i = 0; i < objectCount; i++) {
                objects.add(new Object());
            }
            
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000; // 转换为毫秒
            
            System.out.println("分配 " + objectCount + " 个对象耗时: " + duration + "ms");
            System.out.println("平均分配速度: " + (objectCount * 1000.0 / duration) + " 对象/秒");
            
            // 清理对象，触发GC
            objects.clear();
            System.gc();
        }
    }
    
    /**
     * 主方法 - 演示面试模拟器
     */
    public static void main(String[] args) {
        System.out.println("JVM专家面试模拟器启动...");
        
        // 显示当前JVM信息
        JVMPerformanceTester.printJVMRuntimeInfo();
        
        // 运行性能测试
        JVMPerformanceTester.testAllocationPerformance();
        
        // 启动面试模拟
        InterviewSimulator simulator = new InterviewSimulator();
        simulator.startInterview();
    }
}