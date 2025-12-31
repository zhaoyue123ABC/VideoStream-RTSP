package com.higentec.single;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.bytedeco.javacv.Frame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraToRTSPGUI extends JFrame {

    // 组件
    private JComboBox<String> cameraComboBox;
    private JTextField rtspUrlField;
    private JComboBox<String> resolutionComboBox;
    private JComboBox<Integer> fpsComboBox;
    private JButton previewButton;
    private JButton closePreviewButton;
    private JButton startButton;
    private JButton stopButton;
    private JButton refreshButton;
    private JLabel statusLabel;
    private JLabel previewLabel;
    private JLabel statsLabel;

    // 推流控制
    private StreamController streamController;
    private PreviewThread previewThread;
    private List<String> cameraList;
    private Map<Integer, String> cameraResolutions;
    private JTextArea logArea;

    // 分辨率预设
    private static final String[] RESOLUTIONS = {
            "320x240", "640x480", "800x600",
            "1024x768", "1280x720", "1920x1080"
    };

    // 帧率预设
    private static final Integer[] FPS_OPTIONS = {10, 15, 20, 25, 30};

    // 添加标志位，防止重复点击
    private volatile boolean isRefreshing = false;
    private volatile boolean isPreviewing = false;
    private volatile boolean isStreaming = false;

    public CameraToRTSPGUI() {
        initComponents();

        // 新增代码：设置窗口图标
        setWindowIcon();

        // 改为在Swing事件线程中初始化摄像头检测
        SwingUtilities.invokeLater(() -> {
            detectCamerasImpl();
        });

        // 窗体关闭监听
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                stopAllStreaming();
                System.exit(0);
            }
        });
    }

    /**
     * 设置窗口图标
     */
    private void setWindowIcon() {
        try {
            // 方法1：从项目资源文件加载（推荐）
            // 假设你的图标文件名为icon.png，放在src/main/resources/或src/resources/目录下
            URL iconUrl = getClass().getResource("/avatar.png");
            if (iconUrl != null) {
                ImageIcon icon = new ImageIcon(iconUrl);
                setIconImage(icon.getImage());
                System.out.println("窗口图标设置成功（从资源文件）");
            } else {
                // 方法2：使用绝对路径（备选）
                // ImageIcon icon = new ImageIcon("path/to/your/icon.png");
                // setIconImage(icon.getImage());

                // 方法3：创建简单默认图标（备选方案）
//                createDefaultIcon();
            }
        } catch (Exception e) {
            System.err.println("设置窗口图标失败: " + e.getMessage());
            // 创建默认图标作为后备
//            createDefaultIcon();
        }
    }

    private void initComponents() {
        setTitle("VideoStream-RTSP推流工具 v2026.0104");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(new Color(240, 240, 240));

        // 左侧面板 - 控制区域
        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        leftPanel.setBackground(Color.WHITE);
        leftPanel.setPreferredSize(new Dimension(500, 700));

        JPanel controlPanel = createControlPanel();
        leftPanel.add(controlPanel, BorderLayout.NORTH);

        JPanel logPanel = createLogPanel();
        leftPanel.add(logPanel, BorderLayout.CENTER);

        // 右侧面板 - 视频预览
        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));
        rightPanel.setBorder(new EmptyBorder(10, 5, 10, 10));
        rightPanel.setBackground(Color.WHITE);

        JPanel previewPanel = createPreviewPanel();
        rightPanel.add(previewPanel, BorderLayout.CENTER);

        add(leftPanel, BorderLayout.WEST);
        add(rightPanel, BorderLayout.CENTER);

        pack();
        setSize(1200, 700);
        setMinimumSize(new Dimension(1000, 600));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(createTitledBorder("控制面板", new Color(70, 130, 180)));
        panel.setBackground(Color.WHITE);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 摄像头选择
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(createLabel("摄像头选择:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        cameraComboBox = new JComboBox<>();
        cameraComboBox.setPreferredSize(new Dimension(250, 30));
        cameraComboBox.addActionListener(e -> onCameraSelectionChanged());
        panel.add(cameraComboBox, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 3;
        gbc.gridy = 0;
        refreshButton = createStyledButton("刷新列表", new Color(70, 130, 180));
        refreshButton.addActionListener(e -> {
            if (!isRefreshing) {
                refreshCameras();
            }
        });
        refreshButton.setPreferredSize(new Dimension(100, 30));
        panel.add(refreshButton, gbc);

        // RTSP地址
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 4;
        panel.add(createLabel("RTSP服务器地址:"), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        rtspUrlField = new JTextField("rtsp://localhost:8554/live", 30);
        rtspUrlField.setFont(new Font("宋体", Font.PLAIN, 12));
        rtspUrlField.setToolTipText("RTSP服务器地址，如：rtsp://IP:端口/流名称");
        panel.add(rtspUrlField, gbc);

        // 分辨率设置
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        panel.add(createLabel("视频分辨率:"), gbc);

        gbc.gridx = 2;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        panel.add(createLabel("帧率(FPS):"), gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        resolutionComboBox = new JComboBox<>(RESOLUTIONS);
        resolutionComboBox.setFont(new Font("宋体", Font.PLAIN, 12));
        resolutionComboBox.addActionListener(e -> onResolutionOrFpsChanged());
        panel.add(resolutionComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        fpsComboBox = new JComboBox<>(FPS_OPTIONS);
        fpsComboBox.setSelectedItem(30);
        fpsComboBox.setFont(new Font("宋体", Font.PLAIN, 12));
        fpsComboBox.addActionListener(e -> onResolutionOrFpsChanged());
        panel.add(fpsComboBox, gbc);

        // 操作按钮面板
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 4;
        gbc.insets = new Insets(15, 8, 8, 8);
        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        buttonPanel.setBackground(Color.WHITE);

        previewButton = createStyledButton("\uD83D\uDD0D 打开预览", new Color(30, 144, 255));
        previewButton.addActionListener(e -> {
            if (!isPreviewing) {
                startPreview();
            }
        });
        buttonPanel.add(previewButton);

        closePreviewButton = createStyledButton("X 关闭预览", new Color(255, 140, 0));
        closePreviewButton.addActionListener(e -> {
            if (isPreviewing) {
                closePreview();
            }
        });
        closePreviewButton.setEnabled(false);
        buttonPanel.add(closePreviewButton);

        startButton = createStyledButton("▶ 开始推流", new Color(0, 150, 0));
        startButton.addActionListener(e -> {
            if (!isStreaming) {
                startStreaming();
            }
        });
        buttonPanel.add(startButton);

        stopButton = createStyledButton("⏹ 停止推流", new Color(200, 0, 0));
        stopButton.addActionListener(e -> {
            if (isStreaming) {
                stopStreaming();
            }
        });
        stopButton.setEnabled(false);
        buttonPanel.add(stopButton);

        panel.add(buttonPanel, gbc);

        // 状态显示
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 4;
        gbc.insets = new Insets(15, 8, 8, 8);
        statusLabel = new JLabel("状态: 就绪", SwingConstants.CENTER);
        statusLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));
        statusLabel.setForeground(new Color(0, 100, 0));
        panel.add(statusLabel, gbc);

        // 统计信息
        gbc.gridx = 0;
        gbc.gridy = 7;
        statsLabel = new JLabel("帧数: 0 | 时长: 0s | FPS: 0.0", SwingConstants.CENTER);
        statsLabel.setFont(new Font("宋体", Font.BOLD, 12));
        statsLabel.setForeground(Color.DARK_GRAY);
        panel.add(statsLabel, gbc);

        return panel;
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("微软雅黑", Font.BOLD, 12));
        label.setForeground(Color.BLACK);
        return label;
    }

    private TitledBorder createTitledBorder(String title, Color color) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(color, 2),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 14),
                color
        );
    }

    private JButton createStyledButton(String text, Color bgColor) {
        JButton button = new JButton(text);
        button.setBackground(bgColor);
        button.setForeground(Color.BLACK);
        String[] fontChain = {
                "Segoe UI Emoji",    // 首选：用于显示彩色Emoji和符号
                "Segoe UI Symbol",   // 次选：用于显示单色符号
                "微软雅黑" // 保底：用于显示中文及其他所有字符
        };
        button.setFont(new Font(String.join(", ", fontChain), Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createRaisedBevelBorder());
        button.setPreferredSize(new Dimension(140, 40));

        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(bgColor.brighter());
                button.setForeground(Color.WHITE);
            }

            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(bgColor);
                button.setForeground(Color.BLACK);
            }
        });

        return button;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(createTitledBorder("运行日志", new Color(70, 130, 180)));
        panel.setBackground(Color.WHITE);

        logArea = new JTextArea(15, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font("宋体", Font.PLAIN, 12));
        logArea.setBackground(new Color(245, 245, 245));
        logArea.setForeground(Color.DARK_GRAY);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        JPanel logControlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        logControlPanel.setBackground(Color.WHITE);

        JButton clearLogButton = createStyledButton("清空日志", new Color(100, 100, 100));
        clearLogButton.setPreferredSize(new Dimension(100, 30));
        clearLogButton.addActionListener(e -> logArea.setText(""));
        logControlPanel.add(clearLogButton);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(logControlPanel, BorderLayout.SOUTH);

        redirectSystemOutput();

        return panel;
    }

    private JPanel createPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(createTitledBorder("视频预览", new Color(220, 20, 60)));
        panel.setBackground(Color.WHITE);

        previewLabel = new JLabel("等待启动预览...", SwingConstants.CENTER);
        previewLabel.setPreferredSize(new Dimension(640, 480));
        previewLabel.setBackground(Color.BLACK);
        previewLabel.setOpaque(true);
        previewLabel.setForeground(Color.WHITE);
        previewLabel.setFont(new Font("微软雅黑", Font.BOLD, 16));
        previewLabel.setHorizontalAlignment(SwingConstants.CENTER);
        previewLabel.setVerticalAlignment(SwingConstants.CENTER);

        panel.add(previewLabel, BorderLayout.CENTER);

        return panel;
    }

    private void redirectSystemOutput() {
        try {
            PrintStream printStream = new PrintStream(new TextAreaOutputStream(logArea), true, "UTF-8");
            System.setOut(printStream);
            System.setErr(printStream);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private void refreshCameras() {
        if (isRefreshing) {
            return;
        }

        isRefreshing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText("检测中...");

        logArea.append("[" + getCurrentTime() + "] 刷新摄像头列表...\n");

        // 关闭当前预览
        if (isPreviewing) {
            closePreview();
        }

        // 在单独的线程中检测摄像头
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                detectCamerasImpl();
                return null;
            }

            @Override
            protected void done() {
                SwingUtilities.invokeLater(() -> {
                    refreshButton.setEnabled(true);
                    refreshButton.setText("刷新列表");
                    isRefreshing = false;
                });
            }
        };
        worker.execute();
    }

    private void detectCamerasImpl() {
        logArea.append("[" + getCurrentTime() + "] 正在检测摄像头...\n");
        List<String> newCameraList = new ArrayList<>();
        Map<Integer, String> newCameraResolutions = new HashMap<>();

        for (int i = 0; i < 4; i++) {
            try {
                logArea.append("[" + getCurrentTime() + "] 检测索引 " + i + "...\n");

                VideoCapture capture = new VideoCapture();

                // 尝试使用DirectShow API
                boolean opened = capture.open(i, opencv_videoio.CAP_DSHOW);

                if (opened && capture.isOpened()) {
                    Thread.sleep(300);

                    Mat frame = new Mat();
                    int retryCount = 0;
                    boolean readSuccess = false;

                    while (retryCount < 3 && !readSuccess) {
                        readSuccess = capture.read(frame);
                        if (!readSuccess) {
                            Thread.sleep(100);
                            retryCount++;
                        }
                    }

                    if (readSuccess && !frame.empty()) {
                        int width = frame.cols();
                        int height = frame.rows();
                        String resolution = width + "x" + height;
                        String info = String.format("摄像头 %d (%dx%d)", i, width, height);
                        newCameraList.add(info);
                        newCameraResolutions.put(i, resolution);
                        logArea.append("[" + getCurrentTime() + "] ✓ 找到: " + info + "\n");
                    }

                    frame.release();
                    capture.release();
                }

                Thread.sleep(150);

            } catch (Exception e) {
                // 忽略检测错误
            }
        }

        // 更新UI
        SwingUtilities.invokeLater(() -> {
            String previouslySelected = (String) cameraComboBox.getSelectedItem();
            cameraList = newCameraList;
            cameraResolutions = newCameraResolutions;

            cameraComboBox.removeAllItems();

            for (String camera : cameraList) {
                cameraComboBox.addItem(camera);
            }

            if (cameraList.size() > 0) {
                // 尝试恢复之前的选择
                if (previouslySelected != null) {
                    for (int i = 0; i < cameraList.size(); i++) {
                        if (cameraList.get(i).equals(previouslySelected)) {
                            cameraComboBox.setSelectedIndex(i);
                            break;
                        }
                    }
                }

                statusLabel.setText("状态: 就绪 (" + cameraList.size() + "个摄像头)");
                logArea.append("[" + getCurrentTime() + "] 摄像头检测完成\n");
            } else {
                statusLabel.setText("状态: 未检测到摄像头");
                logArea.append("[" + getCurrentTime() + "] ⚠ 未检测到摄像头\n");
            }
        });
    }

    private void onCameraSelectionChanged() {
        String selected = (String) cameraComboBox.getSelectedItem();
        if (selected != null) {
            try {
                String[] parts = selected.split(" ");
                if (parts.length > 1) {
                    String indexStr = parts[1];
                    if (indexStr.contains("(")) {
                        indexStr = indexStr.substring(0, indexStr.indexOf("("));
                    }
                    int cameraIndex = Integer.parseInt(indexStr);

                    // 获取该摄像头检测到的分辨率
                    String detectedResolution = cameraResolutions.get(cameraIndex);
                    if (detectedResolution != null) {
                        // 在分辨率下拉框中选中该分辨率
                        resolutionComboBox.setSelectedItem(detectedResolution);
                        logArea.append("[" + getCurrentTime() + "] 自动设置分辨率为: " + detectedResolution + "\n");
                    }
                }
            } catch (Exception e) {
                // 解析失败，忽略
            }
        }
    }

    private void onResolutionOrFpsChanged() {
        if (isPreviewing) {
            // 如果正在预览，重新启动预览
            logArea.append("[" + getCurrentTime() + "] 分辨率/帧率已更改，重新启动预览...\n");

            new Thread(() -> {
                // 先关闭预览
                closePreview();

                // 短暂延迟后重新开启
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                SwingUtilities.invokeLater(() -> {
                    if (!isPreviewing) {
                        startPreview();
                    }
                });
            }).start();
        }
    }

    // ==================== 颜色修复的核心方法 ====================

    /**
     * 修复OpenCV BGR到Java RGB的颜色转换
     */
    private BufferedImage matToBufferedImage(Mat mat) {
        if (mat == null || mat.empty()) return null;

        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();

        if (channels == 3) {
            // 创建RGB格式的BufferedImage
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

            // 获取Mat数据
            byte[] data = new byte[width * height * 3];
            mat.data().get(data);

            // 直接进行BGR->RGB转换（修复颜色）
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = (y * width + x) * 3;

                    // OpenCV BGR顺序
                    int b = data[index] & 0xFF;     // 蓝色
                    int g = data[index + 1] & 0xFF; // 绿色
                    int r = data[index + 2] & 0xFF; // 红色

                    // 转换为RGB：r << 16 | g << 8 | b
                    int rgb = (r << 16) | (g << 8) | b;
                    image.setRGB(x, y, rgb);
                }
            }

            return image;
        } else {
            // 灰度图或其他格式
            int type = (channels == 1) ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_4BYTE_ABGR;

            byte[] data = new byte[channels * width * height];
            mat.data().get(data);

            BufferedImage image = new BufferedImage(width, height, type);
            image.getRaster().setDataElements(0, 0, width, height, data);

            return image;
        }
    }

    private void startPreview() {
        if (isPreviewing) {
            return;
        }

        int cameraIndex = getSelectedCameraIndex();
        if (cameraIndex < 0) {
            JOptionPane.showMessageDialog(this, "请先选择摄像头", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String[] resolution = ((String) resolutionComboBox.getSelectedItem()).split("x");
        int width = Integer.parseInt(resolution[0]);
        int height = Integer.parseInt(resolution[1]);
        int fps = (Integer) fpsComboBox.getSelectedItem();

        logArea.append("[" + getCurrentTime() + "] 开始预览...\n");
        logArea.append("[" + getCurrentTime() + "] 摄像头索引: " + cameraIndex + "\n");
        logArea.append("[" + getCurrentTime() + "] 分辨率: " + width + "x" + height + "\n");
        logArea.append("[" + getCurrentTime() + "] 帧率: " + fps + "fps\n");
        logArea.append("[" + getCurrentTime() + "] 颜色修复: 启用\n");

        // 更新UI状态
        isPreviewing = true;
        previewButton.setEnabled(false);
        closePreviewButton.setEnabled(true);
        statusLabel.setText("状态: 预览中...");
        statusLabel.setForeground(new Color(30, 144, 255));

        // 创建预览线程
        previewThread = new PreviewThread(cameraIndex, width, height, fps);
        previewThread.start();
    }

    private void closePreview() {
        if (!isPreviewing) {
            return;
        }

        logArea.append("[" + getCurrentTime() + "] 关闭预览...\n");

        isPreviewing = false;

        if (previewThread != null) {
            previewThread.stopPreview();
            previewThread = null;
        }

        SwingUtilities.invokeLater(() -> {
            previewButton.setEnabled(true);
            closePreviewButton.setEnabled(false);

            previewLabel.setIcon(null);
            previewLabel.setText("预览已关闭");
            previewLabel.setForeground(Color.WHITE);

            statusLabel.setText("状态: 就绪");
            statusLabel.setForeground(new Color(0, 100, 0));
        });

        logArea.append("[" + getCurrentTime() + "] 预览已关闭\n");
    }

    private void startStreaming() {
        if (isStreaming) {
            return;
        }

        try {
            int cameraIndex = getSelectedCameraIndex();
            if (cameraIndex < 0) {
                JOptionPane.showMessageDialog(this, "请先选择摄像头", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String rtspUrl = rtspUrlField.getText().trim();
            if (rtspUrl.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入RTSP地址", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String[] resolution = ((String) resolutionComboBox.getSelectedItem()).split("x");
            int width = Integer.parseInt(resolution[0]);
            int height = Integer.parseInt(resolution[1]);
            int fps = (Integer) fpsComboBox.getSelectedItem();

            logArea.append("[" + getCurrentTime() + "] 开始推流...\n");
            logArea.append("[" + getCurrentTime() + "] RTSP地址: " + rtspUrl + "\n");
            logArea.append("[" + getCurrentTime() + "] 分辨率: " + width + "x" + height + "\n");
            logArea.append("[" + getCurrentTime() + "] 帧率: " + fps + "fps\n");
            logArea.append("[" + getCurrentTime() + "] 颜色修复: 启用\n");

            // 更新UI状态
            isStreaming = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            statusLabel.setText("状态: 推流中...");
            statusLabel.setForeground(new Color(0, 150, 0));

            // 停止现有的流
            if (streamController != null) {
                streamController.stopStreaming();
                streamController = null;
            }

            streamController = new StreamController();

            // 在单独的线程中启动推流
            new Thread(() -> {
                try {
                    streamController.startStreaming(cameraIndex, rtspUrl, width, height, fps);
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        isStreaming = false;
                        statusLabel.setText("状态: 推流失败");
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                        logArea.append("[" + getCurrentTime() + "] 推流失败: " + e.getMessage() + "\n");
                    });
                }
            }).start();

        } catch (Exception e) {
            logArea.append("[" + getCurrentTime() + "] 启动错误: " + e.getMessage() + "\n");
            isStreaming = false;
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }

    private int getSelectedCameraIndex() {
        String selected = (String) cameraComboBox.getSelectedItem();
        if (selected == null) {
            return -1;
        }

        try {
            String[] parts = selected.split(" ");
            if (parts.length > 1) {
                String indexStr = parts[1];
                if (indexStr.contains("(")) {
                    indexStr = indexStr.substring(0, indexStr.indexOf("("));
                }
                return Integer.parseInt(indexStr);
            }
        } catch (Exception e) {
            // 解析失败
        }

        return -1;
    }

    private void stopStreaming() {
        if (!isStreaming) {
            return;
        }

        logArea.append("[" + getCurrentTime() + "] 停止推流...\n");

        isStreaming = false;

        if (streamController != null) {
            streamController.stopStreaming();
            streamController = null;
        }

        SwingUtilities.invokeLater(() -> {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            statusLabel.setText("状态: 已停止");
            statusLabel.setForeground(Color.BLUE);
            statsLabel.setText("帧数: 0 | 时长: 0s | FPS: 0.0");
        });

        logArea.append("[" + getCurrentTime() + "] 推流已停止\n");
    }

    private void stopAllStreaming() {
        closePreview();
        stopStreaming();
        logArea.append("[" + getCurrentTime() + "] 程序关闭\n");
    }

    private String getCurrentTime() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // ==================== 预览线程类 ====================

    class PreviewThread extends Thread {
        private final AtomicBoolean isRunning = new AtomicBoolean(true);
        private VideoCapture capture;
        private final int cameraIndex;
        private int width;
        private int height;
        private int fps;

        public PreviewThread(int cameraIndex, int width, int height, int fps) {
            this.cameraIndex = cameraIndex;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        @Override
        public void run() {
            try {
                logArea.append("[" + getCurrentTime() + "] 预览初始化...\n");

                capture = new VideoCapture();

                // 尝试使用DirectShow API
                boolean opened = capture.open(cameraIndex, opencv_videoio.CAP_DSHOW);

                if (!opened || !capture.isOpened()) {
                    throw new Exception("无法打开摄像头");
                }

                // 等待摄像头初始化
                Thread.sleep(500);

                // 设置摄像头属性
                try {
                    capture.set(opencv_videoio.CAP_PROP_FRAME_WIDTH, width);
                    capture.set(opencv_videoio.CAP_PROP_FRAME_HEIGHT, height);
                    capture.set(opencv_videoio.CAP_PROP_FPS, fps);
                } catch (Exception e) {
                    logArea.append("[" + getCurrentTime() + "] 使用默认摄像头设置\n");
                }

                // 测试读取帧
                Mat testFrame = new Mat();
                int retryCount = 0;
                boolean readSuccess = false;

                while (retryCount < 10 && !readSuccess && isRunning.get()) {
                    readSuccess = capture.read(testFrame);
                    if (!readSuccess) {
                        retryCount++;
                        Thread.sleep(200);
                    }
                }

                if (!readSuccess || testFrame.empty()) {
                    throw new Exception("摄像头可打开但无法读取帧");
                }

                logArea.append("[" + getCurrentTime() + "] ✓ 预览初始化成功\n");
                logArea.append("[" + getCurrentTime() + "]   实际分辨率: " + testFrame.cols() + "x" + testFrame.rows() + "\n");

                SwingUtilities.invokeLater(() -> {
                    previewLabel.setText("摄像头连接成功");
                    previewLabel.setForeground(Color.GREEN);
                });

                testFrame.release();

                // 主预览循环
                long frameCount = 0;
                long lastLogTime = System.currentTimeMillis();

                while (isRunning.get()) {
                    try {
                        Mat frame = new Mat();
                        boolean frameRead = capture.read(frame);

                        if (frameRead && !frame.empty()) {
                            // 转换为BufferedImage（自动修复颜色）
                            BufferedImage image = matToBufferedImage(frame);

                            if (image != null) {
                                // 显示图像
                                int labelWidth = previewLabel.getWidth();
                                int labelHeight = previewLabel.getHeight();

                                if (labelWidth > 10 && labelHeight > 10) {
                                    // 保持宽高比缩放
                                    double widthRatio = (double) labelWidth / image.getWidth();
                                    double heightRatio = (double) labelHeight / image.getHeight();
                                    double ratio = Math.min(widthRatio, heightRatio);

                                    int scaledWidth = (int) (image.getWidth() * ratio);
                                    int scaledHeight = (int) (image.getHeight() * ratio);

                                    // 居中显示
                                    BufferedImage centeredImage = new BufferedImage(
                                            labelWidth, labelHeight, BufferedImage.TYPE_INT_RGB
                                    );
                                    Graphics2D g2d = centeredImage.createGraphics();
                                    g2d.setColor(Color.BLACK);
                                    g2d.fillRect(0, 0, labelWidth, labelHeight);

                                    int x = (labelWidth - scaledWidth) / 2;
                                    int y = (labelHeight - scaledHeight) / 2;

                                    Image scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
                                    g2d.drawImage(scaledImage, x, y, null);
                                    g2d.dispose();

                                    SwingUtilities.invokeLater(() -> {
                                        previewLabel.setIcon(new ImageIcon(centeredImage));
                                        previewLabel.setText("");
                                    });
                                }

                                frameCount++;

                                // 记录状态
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime > 5000) {
                                    double actualFps = frameCount / 5.0;
                                    logArea.append("[" + getCurrentTime() + "] 预览FPS: " + String.format("%.1f", actualFps) + "\n");
                                    frameCount = 0;
                                    lastLogTime = currentTime;
                                }
                            }
                        }

                        frame.release();

                        // 控制帧率
                        Thread.sleep(Math.max(1, 1000 / fps));

                    } catch (Exception e) {
                        if (isRunning.get()) {
                            Thread.sleep(100);
                        }
                    }
                }

            } catch (Exception e) {
                logArea.append("[" + getCurrentTime() + "] 预览失败: " + e.getMessage() + "\n");

                SwingUtilities.invokeLater(() -> {
                    previewLabel.setText("预览失败: " + e.getMessage());
                    previewLabel.setForeground(Color.RED);
                    closePreview();
                });

            } finally {
                if (capture != null && capture.isOpened()) {
                    capture.release();
                }

                SwingUtilities.invokeLater(() -> {
                    isPreviewing = false;
                    previewButton.setEnabled(true);
                    closePreviewButton.setEnabled(false);
                });
            }
        }

        public void stopPreview() {
            isRunning.set(false);
        }
    }

    // ==================== 推流控制器类 ====================

    class StreamController {
        private final AtomicBoolean isRunning = new AtomicBoolean(true);
        private FFmpegFrameRecorder recorder;
        private VideoCapture capture;
        private long frameCount = 0;
        private long startTime = 0;

        public void startStreaming(int cameraIndex, String rtspUrl,
                                   int width, int height, int fps) throws Exception {

            if (!isRunning.get()) {
                return;
            }

            frameCount = 0;
            startTime = System.currentTimeMillis();

            logArea.append("[" + getCurrentTime() + "] 初始化推流...\n");

            try {
                // 使用VideoCapture模式
                capture = new VideoCapture();
                boolean opened = capture.open(cameraIndex, opencv_videoio.CAP_DSHOW);

                if (!opened || !capture.isOpened()) {
                    throw new Exception("无法打开摄像头");
                }

                Thread.sleep(500);

                // 设置摄像头属性
                capture.set(opencv_videoio.CAP_PROP_FRAME_WIDTH, width);
                capture.set(opencv_videoio.CAP_PROP_FRAME_HEIGHT, height);
                capture.set(opencv_videoio.CAP_PROP_FPS, fps);

                logArea.append("[" + getCurrentTime() + "] 推流摄像头打开成功\n");

                // 创建RTSP录制器
                recorder = new FFmpegFrameRecorder(rtspUrl, width, height);
                recorder.setFormat("rtsp");
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                recorder.setFrameRate(fps);
                recorder.setVideoBitrate(2000000);
                recorder.setVideoQuality(23);
                recorder.setGopSize(fps * 2);
                recorder.setOption("rtsp_transport", "tcp");
                recorder.setOption("tune", "zerolatency");
                recorder.setOption("preset", "ultrafast");

                recorder.start();
                logArea.append("[" + getCurrentTime() + "] RTSP推流已启动\n");

                Java2DFrameConverter converter = new Java2DFrameConverter();

                // 推流循环
                while (isRunning.get()) {
                    try {
                        Frame frame = null;

                        if (capture != null && capture.isOpened()) {
                            Mat mat = new Mat();
                            if (capture.read(mat) && !mat.empty()) {
                                // 转换为BufferedImage（自动修复颜色）
                                BufferedImage image = matToBufferedImage(mat);

                                if (image != null) {
                                    frame = converter.convert(image);
                                }
                                mat.release();
                            }
                        }

                        if (frame != null) {
                            frame.timestamp = 1000000L * (System.currentTimeMillis() - startTime);
                            recorder.record(frame);
                            frameCount++;

                            if (frameCount % 30 == 0) {
                                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                                double actualFps = frameCount / (elapsed > 0 ? elapsed : 1);

                                SwingUtilities.invokeLater(() -> {
                                    statsLabel.setText(String.format("帧数: %d | 时长: %ds | FPS: %.1f",
                                            frameCount, elapsed, actualFps));
                                });
                            }
                        }

                        Thread.sleep(Math.max(1, 1000 / fps));

                    } catch (Exception e) {
                        if (isRunning.get()) {
                            String msg = e.getMessage();
                            if (msg == null || !msg.contains("timestamp")) {
                                logArea.append("[" + getCurrentTime() + "] 推流帧错误: " + msg + "\n");
                            }
                            Thread.sleep(50);
                        }
                    }
                }

            } finally {
                stopInternal();
            }
        }

        private void stopInternal() {
            isRunning.set(false);

            try {
                if (recorder != null) {
                    recorder.stop();
                    recorder.release();
                    logArea.append("[" + getCurrentTime() + "] RTSP录制器已停止\n");
                }
            } catch (Exception e) {
                // 忽略
            }

            try {
                if (capture != null && capture.isOpened()) {
                    capture.release();
                    logArea.append("[" + getCurrentTime() + "] 推流摄像头已关闭\n");
                }
            } catch (Exception e) {
                // 忽略
            }
        }

        public void stopStreaming() {
            isRunning.set(false);
        }
    }

    // ==================== 自定义输出流 ====================

    static class TextAreaOutputStream extends ByteArrayOutputStream {
        private final JTextArea textArea;

        public TextAreaOutputStream(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public void flush() {
            String text = this.toString();
            if (!text.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    textArea.append(text);
                    textArea.setCaretPosition(textArea.getDocument().getLength());
                });
                this.reset();
            }
        }
    }

    public static void main(String[] args) {
        // 设置系统属性
        System.setProperty("org.bytedeco.javacpp.loadflycapture", "false");
        System.setProperty("org.bytedeco.javacpp.loadlibfreenect2", "false");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            new CameraToRTSPGUI();
        });
    }
}