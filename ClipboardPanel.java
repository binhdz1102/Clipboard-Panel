import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.*;
import java.util.List;

public class ClipboardPanelSwing extends JFrame {
    private static final String APP_TITLE = "Clipboard Panel";
    private static final String DATA_FILE_NAME = "templates.json";
    private static final String ADD_ICON_FILE_NAME = "add_button.png";

    private static final int DEFAULT_GRID_COLUMNS = 4;
    private static final int MAX_GRID_COLUMNS = 8;
    private static final int BUTTON_SIZE = 88;
    private static final int CELL_GAP = 12;
    private static final int SINGLE_CLICK_DELAY_MS = 280;

    private final Path appDir;
    private final Path dataFile;
    private final Path addIconFile;

    private final java.util.List<TemplateItem> templates = new ArrayList<TemplateItem>();
    private final JPanel gridPanel = new JPanel();
    private final JLabel statusLabel = new JLabel();
    private ImageIcon addIcon;

    private javax.swing.Timer pendingClickTimer;
    private String pendingClickTemplateId;

    private int currentGridColumns = DEFAULT_GRID_COLUMNS;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // Keep default Swing look and feel if system look and feel is unavailable.
                }

                ClipboardPanelSwing app = new ClipboardPanelSwing();
                app.setVisible(true);
            }
        });
    }

    public ClipboardPanelSwing() {
        this.appDir = resolveAppDir();
        this.dataFile = appDir.resolve(DATA_FILE_NAME);
        this.addIconFile = appDir.resolve(ADD_ICON_FILE_NAME);

        setTitle(APP_TITLE);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(720, 520);
        setMinimumSize(new Dimension(620, 420));
        setLocationRelativeTo(null);

        this.addIcon = loadAddIcon();
        loadTemplatesFromDisk(false);
        buildUi();
        renderGrid();
    }

    private Path resolveAppDir() {
        try {
            CodeSource codeSource = ClipboardPanelSwing.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path location = Paths.get(codeSource.getLocation().toURI()).toAbsolutePath();
                if (Files.isRegularFile(location)) {
                    return location.getParent();
                }
                return location;
            }
        } catch (URISyntaxException ignored) {
            // Fallback below.
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JPanel top = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);

        JLabel titleLabel = new JLabel(APP_TITLE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        top.add(titleLabel, BorderLayout.WEST);

        JButton reloadButton = new JButton("Reload");
        reloadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadTemplatesFromDisk(true);
                renderGrid();
            }
        });
        top.add(reloadButton, BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        root.add(center, BorderLayout.CENTER);

        JLabel helpLabel = new JLabel("Single-click to copy. Double-click to edit or delete. Use + to add a new template.");
        helpLabel.setForeground(new Color(85, 85, 85));
        center.add(helpLabel, BorderLayout.NORTH);

        gridPanel.setBackground(root.getBackground());
        JScrollPane scrollPane = new JScrollPane(gridPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        center.add(scrollPane, BorderLayout.CENTER);

        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int newColumns = calculateGridColumns(scrollPane.getViewport().getWidth());
                if (newColumns != currentGridColumns) {
                    currentGridColumns = newColumns;
                    renderGrid();
                }
            }
        });

        statusLabel.setText("Data file: " + dataFile.toString());
        statusLabel.setForeground(new Color(47, 111, 47));
        root.add(statusLabel, BorderLayout.SOUTH);
    }

    private int calculateGridColumns(int width) {
        if (width <= 0) {
            width = getWidth();
        }
        int cellWidth = BUTTON_SIZE + CELL_GAP;
        int columns = Math.max(1, width / cellWidth);
        return Math.min(columns, MAX_GRID_COLUMNS);
    }

    private ImageIcon loadAddIcon() {
        if (!Files.exists(addIconFile)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(addIconFile.toFile());
            if (image == null) {
                return null;
            }
            int target = 38;
            int width = image.getWidth();
            int height = image.getHeight();
            double scale = Math.min((double) target / width, (double) target / height);
            int scaledW = Math.max(1, (int) Math.round(width * scale));
            int scaledH = Math.max(1, (int) Math.round(height * scale));
            Image scaled = image.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void renderGrid() {
        gridPanel.removeAll();
        int columns = Math.max(1, currentGridColumns);
        int totalItems = templates.size() + 1; // existing templates + Add button
        int totalRows = Math.max(1, (totalItems + columns - 1) / columns);

        gridPanel.setLayout(new GridBagLayout());

        int index = 0;
        for (TemplateItem item : templates) {
            gridPanel.add(createTemplateButton(item), createButtonConstraints(index, columns));
            index++;
        }

        gridPanel.add(createAddButton(), createButtonConstraints(index, columns));

        // Horizontal filler: keeps real cells packed at the left side.
        GridBagConstraints hFiller = new GridBagConstraints();
        hFiller.gridx = columns;
        hFiller.gridy = 0;
        hFiller.gridheight = totalRows;
        hFiller.weightx = 1.0;
        hFiller.weighty = 0.0;
        hFiller.fill = GridBagConstraints.HORIZONTAL;
        gridPanel.add(Box.createHorizontalGlue(), hFiller);

        // Vertical filler must be placed AFTER the last row.
        // Putting weighty on row 0 stretches the first row and creates a huge gap
        // between the first and second row, which was the layout bug in the screenshot.
        GridBagConstraints vFiller = new GridBagConstraints();
        vFiller.gridx = 0;
        vFiller.gridy = totalRows;
        vFiller.gridwidth = columns + 1;
        vFiller.weightx = 1.0;
        vFiller.weighty = 1.0;
        vFiller.fill = GridBagConstraints.BOTH;
        gridPanel.add(Box.createGlue(), vFiller);

        gridPanel.revalidate();
        gridPanel.repaint();
    }

    private GridBagConstraints createButtonConstraints(int index, int columns) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = index % columns;
        gbc.gridy = index / columns;
        gbc.insets = new Insets(CELL_GAP / 2, CELL_GAP / 2, CELL_GAP / 2, CELL_GAP / 2);
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        return gbc;
    }

    private JButton createTemplateButton(final TemplateItem item) {
        JButton button = new WrappedTextButton(item.title);
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setFocusPainted(false);
        button.setBackground(new Color(244, 247, 251));
        button.setToolTipText(item.title);

        // Keep wrapped HTML text visually centered inside the square button.
        // Some Windows look-and-feel defaults add asymmetric margin / text placement,
        // so reset those values explicitly.
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.CENTER);
        button.setIconTextGap(0);
        button.setMargin(new Insets(0, 0, 0, 0));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                handleTemplateClick(item, e.getClickCount());
            }
        });
        return button;
    }

    private JButton createAddButton() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setFocusPainted(false);
        button.setBackground(new Color(238, 247, 238));
        button.setToolTipText("Add Template");

        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.CENTER);
        button.setIconTextGap(0);
        button.setMargin(new Insets(0, 0, 0, 0));

        if (addIcon != null) {
            button.setIcon(addIcon);
        } else {
            button.setText("+");
            button.setFont(button.getFont().deriveFont(Font.BOLD, 28f));
        }

        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAddDialog();
            }
        });
        return button;
    }

    private String toHtmlButtonText(String text) {
        String safe = escapeHtml(text);
        int contentWidth = BUTTON_SIZE - 12;
        return "<html><body style='margin:0;padding:0;text-align:center;'>"
                + "<div style='text-align:center;width:" + contentWidth + "px;'>"
                + safe
                + "</div></body></html>";
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void handleTemplateClick(final TemplateItem item, int clickCount) {
        if (clickCount >= 2) {
            if (pendingClickTimer != null) {
                pendingClickTimer.stop();
                pendingClickTimer = null;
            }
            pendingClickTemplateId = null;
            openEditDialog(item);
            return;
        }

        if (pendingClickTimer != null) {
            pendingClickTimer.stop();
        }
        pendingClickTemplateId = item.id;
        pendingClickTimer = new javax.swing.Timer(SINGLE_CLICK_DELAY_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (item.id.equals(pendingClickTemplateId)) {
                    copyTemplate(item);
                }
                pendingClickTimer = null;
                pendingClickTemplateId = null;
            }
        });
        pendingClickTimer.setRepeats(false);
        pendingClickTimer.start();
    }

    private void copyTemplate(TemplateItem item) {
        StringSelection selection = new StringSelection(item.template);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        statusLabel.setText("Copied template: " + item.title);
    }

    private void openAddDialog() {
        TemplateDialog dialog = new TemplateDialog(this, "Add Template", "", "", false);
        dialog.setVisible(true);
        if (dialog.wasSaved()) {
            templates.add(new TemplateItem(UUID.randomUUID().toString(), dialog.getTemplateTitle(), dialog.getTemplateText()));
            saveTemplatesToDisk();
            renderGrid();
            statusLabel.setText("Added template: " + dialog.getTemplateTitle());
        }
    }

    private void openEditDialog(final TemplateItem item) {
        TemplateDialog dialog = new TemplateDialog(this, "Edit Template: " + item.title, item.title, item.template, true);
        dialog.setVisible(true);

        if (dialog.wasDeleted()) {
            Iterator<TemplateItem> iterator = templates.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().id.equals(item.id)) {
                    iterator.remove();
                    break;
                }
            }
            saveTemplatesToDisk();
            renderGrid();
            statusLabel.setText("Deleted template");
            return;
        }

        if (dialog.wasSaved()) {
            item.title = dialog.getTemplateTitle();
            item.template = dialog.getTemplateText();
            saveTemplatesToDisk();
            renderGrid();
            statusLabel.setText("Updated template: " + item.title);
        }
    }

    private void loadTemplatesFromDisk(boolean showStatus) {
        templates.clear();

        if (!Files.exists(dataFile)) {
            if (showStatus) {
                statusLabel.setText("No templates.json found. Created an empty panel.");
            }
            return;
        }

        try {
            String json = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
            Object root = new JsonParser(json).parse();
            Object rawItems = root;
            if (root instanceof Map) {
                rawItems = ((Map<?, ?>) root).get("templates");
            }
            if (!(rawItems instanceof List)) {
                throw new IOException("templates.json must contain a templates array.");
            }
            for (Object rawItem : (List<?>) rawItems) {
                if (!(rawItem instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) rawItem;
                String title = stringValue(map.get("title")).trim();
                String template = stringValue(map.get("template"));
                String id = stringValue(map.get("id"));
                if (title.length() > 0 && template.length() > 0) {
                    templates.add(new TemplateItem(id.length() > 0 ? id : UUID.randomUUID().toString(), title, template));
                }
            }
            if (showStatus) {
                statusLabel.setText("Reloaded templates.json");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not read templates.json:\n" + ex.getMessage(),
                    "JSON read error",
                    JOptionPane.ERROR_MESSAGE
            );
            if (showStatus) {
                statusLabel.setText("Failed to reload templates.json");
            }
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void saveTemplatesToDisk() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"templates\": [\n");
        for (int i = 0; i < templates.size(); i++) {
            TemplateItem item = templates.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": ").append(jsonString(item.id)).append(",\n");
            sb.append("      \"title\": ").append(jsonString(item.title)).append(",\n");
            sb.append("      \"template\": ").append(jsonString(item.template)).append("\n");
            sb.append("    }");
            if (i < templates.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        try {
            Files.write(dataFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save templates.json:\n" + ex.getMessage(),
                    "JSON save error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private String jsonString(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static class WrappedTextButton extends JButton {
        private final String displayText;

        WrappedTextButton(String displayText) {
            super("");
            this.displayText = displayText == null ? "" : displayText.trim();
            setText("");
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setVerticalTextPosition(SwingConstants.CENTER);
            setIconTextGap(0);
            setMargin(new Insets(0, 0, 0, 0));
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (displayText.length() == 0) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                Insets insets = getInsets();
                int maxWidth = Math.max(12, getWidth() - insets.left - insets.right - 12);
                int maxHeight = Math.max(12, getHeight() - insets.top - insets.bottom - 12);

                Font baseFont = getFont();
                Font drawFont = baseFont;
                FontMetrics metrics = g2.getFontMetrics(drawFont);
                java.util.List<String> lines = wrapText(displayText, metrics, maxWidth);

                // Try a slightly smaller font before giving up. This helps long titles
                // like "Build and install Media apk" fit without being clipped.
                for (float size = baseFont.getSize2D(); size >= 9f; size -= 1f) {
                    drawFont = baseFont.deriveFont(size);
                    metrics = g2.getFontMetrics(drawFont);
                    lines = wrapText(displayText, metrics, maxWidth);
                    if (lines.size() * metrics.getHeight() <= maxHeight) {
                        break;
                    }
                }

                int maxLines = Math.max(1, maxHeight / Math.max(1, metrics.getHeight()));
                if (lines.size() > maxLines) {
                    java.util.List<String> fitted = new ArrayList<String>();
                    for (int i = 0; i < maxLines; i++) {
                        fitted.add(lines.get(i));
                    }
                    int last = fitted.size() - 1;
                    fitted.set(last, ellipsize(fitted.get(last), metrics, maxWidth));
                    lines = fitted;
                }

                int totalHeight = lines.size() * metrics.getHeight();
                int y = (getHeight() - totalHeight) / 2 + metrics.getAscent();

                Color textColor = isEnabled() ? getForeground() : UIManager.getColor("Button.disabledText");
                if (textColor == null) {
                    textColor = Color.GRAY;
                }
                g2.setFont(drawFont);
                g2.setColor(textColor);

                for (String line : lines) {
                    int textWidth = metrics.stringWidth(line);
                    int x = (getWidth() - textWidth) / 2;
                    g2.drawString(line, x, y);
                    y += metrics.getHeight();
                }
            } finally {
                g2.dispose();
            }
        }

        private static java.util.List<String> wrapText(String text, FontMetrics metrics, int maxWidth) {
            java.util.List<String> result = new ArrayList<String>();
            String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
            if (normalized.length() == 0) {
                result.add("");
                return result;
            }

            String[] words = normalized.split("\\s+");
            StringBuilder line = new StringBuilder();

            for (String word : words) {
                if (word.length() == 0) {
                    continue;
                }

                if (line.length() == 0) {
                    if (metrics.stringWidth(word) <= maxWidth) {
                        line.append(word);
                    } else {
                        java.util.List<String> chunks = splitLongWord(word, metrics, maxWidth);
                        for (int i = 0; i < chunks.size() - 1; i++) {
                            result.add(chunks.get(i));
                        }
                        line.append(chunks.get(chunks.size() - 1));
                    }
                    continue;
                }

                String candidate = line.toString() + " " + word;
                if (metrics.stringWidth(candidate) <= maxWidth) {
                    line.append(' ').append(word);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    if (metrics.stringWidth(word) <= maxWidth) {
                        line.append(word);
                    } else {
                        java.util.List<String> chunks = splitLongWord(word, metrics, maxWidth);
                        for (int i = 0; i < chunks.size() - 1; i++) {
                            result.add(chunks.get(i));
                        }
                        line.append(chunks.get(chunks.size() - 1));
                    }
                }
            }

            if (line.length() > 0) {
                result.add(line.toString());
            }
            return result;
        }

        private static java.util.List<String> splitLongWord(String word, FontMetrics metrics, int maxWidth) {
            java.util.List<String> chunks = new ArrayList<String>();
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char ch = word.charAt(i);
                String candidate = chunk.toString() + ch;
                if (chunk.length() > 0 && metrics.stringWidth(candidate) > maxWidth) {
                    chunks.add(chunk.toString());
                    chunk.setLength(0);
                }
                chunk.append(ch);
            }
            if (chunk.length() > 0) {
                chunks.add(chunk.toString());
            }
            if (chunks.isEmpty()) {
                chunks.add(word);
            }
            return chunks;
        }

        private static String ellipsize(String line, FontMetrics metrics, int maxWidth) {
            String ellipsis = "...";
            if (metrics.stringWidth(line) <= maxWidth) {
                return line;
            }
            String value = line;
            while (value.length() > 0 && metrics.stringWidth(value + ellipsis) > maxWidth) {
                value = value.substring(0, value.length() - 1);
            }
            return value.length() == 0 ? ellipsis : value + ellipsis;
        }
    }

    private static class TemplateItem {
        String id;
        String title;
        String template;

        TemplateItem(String id, String title, String template) {
            this.id = id;
            this.title = title;
            this.template = template;
        }
    }

    private static class TemplateDialog extends JDialog {
        private final JTextField titleField = new JTextField();
        private final JTextArea templateArea = new JTextArea();
        private boolean saved = false;
        private boolean deleted = false;

        TemplateDialog(Frame owner, String dialogTitle, String initialTitle, String initialTemplate, boolean showDelete) {
            super(owner, dialogTitle, true);
            setMinimumSize(new Dimension(520, 420));
            setSize(560, 460);
            setLocationRelativeTo(owner);

            JPanel root = new JPanel(new BorderLayout(0, 12));
            root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            setContentPane(root);

            JPanel top = new JPanel(new BorderLayout(0, 4));
            top.add(new JLabel("Display name"), BorderLayout.NORTH);
            titleField.setText(initialTitle);
            top.add(titleField, BorderLayout.CENTER);
            root.add(top, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(0, 4));
            center.add(new JLabel("Template text"), BorderLayout.NORTH);
            templateArea.setText(initialTemplate);
            templateArea.setLineWrap(true);
            templateArea.setWrapStyleWord(true);
            templateArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane textScroll = new JScrollPane(templateArea);
            center.add(textScroll, BorderLayout.CENTER);
            root.add(center, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new BorderLayout());
            JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton cancelButton = new JButton("Cancel");
            JButton saveButton = new JButton("Save");
            rightButtons.add(saveButton);
            rightButtons.add(cancelButton);
            buttons.add(rightButtons, BorderLayout.EAST);

            if (showDelete) {
                JButton deleteButton = new JButton("Delete");
                deleteButton.setBackground(new Color(255, 221, 221));
                deleteButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        int confirm = JOptionPane.showConfirmDialog(
                                TemplateDialog.this,
                                "Are you sure you want to delete this template?",
                                "Delete template",
                                JOptionPane.YES_NO_OPTION
                        );
                        if (confirm == JOptionPane.YES_OPTION) {
                            deleted = true;
                            dispose();
                        }
                    }
                });
                buttons.add(deleteButton, BorderLayout.WEST);
            }

            root.add(buttons, BorderLayout.SOUTH);

            saveButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    save();
                }
            });
            cancelButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });

            getRootPane().setDefaultButton(saveButton);
            getRootPane().registerKeyboardAction(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

            getRootPane().registerKeyboardAction(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    save();
                }
            }, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);

            titleField.requestFocusInWindow();
        }

        private void save() {
            String title = titleField.getText().trim();
            String template = templateArea.getText();
            if (title.length() == 0) {
                JOptionPane.showMessageDialog(this, "Please enter a display name for this template.", "Missing name", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (template.trim().length() == 0) {
                JOptionPane.showMessageDialog(this, "Please enter template text.", "Missing template", JOptionPane.WARNING_MESSAGE);
                return;
            }
            saved = true;
            dispose();
        }

        boolean wasSaved() {
            return saved;
        }

        boolean wasDeleted() {
            return deleted;
        }

        String getTemplateTitle() {
            return titleField.getText().trim();
        }

        String getTemplateText() {
            return templateArea.getText();
        }
    }

    private static class JsonParser {
        private final String text;
        private int pos = 0;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() throws IOException {
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw error("Unexpected trailing data");
            }
            return value;
        }

        private Object parseValue() throws IOException {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            char ch = text.charAt(pos);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseArray();
            if (ch == '"') return parseString();
            if (ch == 't') return parseLiteral("true", Boolean.TRUE);
            if (ch == 'f') return parseLiteral("false", Boolean.FALSE);
            if (ch == 'n') return parseLiteral("null", null);
            if (ch == '-' || (ch >= '0' && ch <= '9')) return parseNumber();
            throw error("Unexpected character: " + ch);
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() throws IOException {
            expect('[');
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (pos >= text.length()) {
                        throw error("Invalid escape sequence");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw error("Invalid unicode escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw error("Invalid unicode escape: \\u" + hex);
                            }
                            pos += 4;
                            break;
                        default:
                            throw error("Unsupported escape sequence: \\" + esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw error("Unterminated string");
        }

        private Object parseLiteral(String literal, Object value) throws IOException {
            if (!text.startsWith(literal, pos)) {
                throw error("Expected " + literal);
            }
            pos += literal.length();
            return value;
        }

        private Number parseNumber() throws IOException {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            String number = text.substring(start, pos);
            try {
                if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException ex) {
                throw error("Invalid number: " + number);
            }
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        private boolean peek(char expected) {
            return pos < text.length() && text.charAt(pos) == expected;
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private IOException error(String message) {
            return new IOException(message + " at position " + pos);
        }
    }
}
