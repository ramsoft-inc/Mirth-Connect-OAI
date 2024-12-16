/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.client.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

import com.mirth.connect.client.ui.util.DisplayUtil;
import com.mirth.connect.donkey.model.channel.DebugOptions;

import net.miginfocom.swing.MigLayout;

public class DeployInDebugModeDialog extends MirthDialog {

    private Frame parent;
    private DebugOptions debugOptions;
    private boolean isDebugChannel = false;

    public DeployInDebugModeDialog() {
        super(PlatformUI.MIRTH_FRAME);
        this.parent = PlatformUI.MIRTH_FRAME;
        debugOptions = new DebugOptions();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        DisplayUtil.setResizable(this, false);
        setPreferredSize(new Dimension(400, 300));
        setModal(true);

        initComponents();
        initLayout();

        pack();

        Dimension dlgSize = getPreferredSize();
        Dimension frmSize = parent.getSize();
        Point loc = parent.getLocation();

        if ((frmSize.width == 0 && frmSize.height == 0) || (loc.x == 0 && loc.y == 0)) {
            setLocationRelativeTo(null);
        } else {
            setLocation((frmSize.width - dlgSize.width) / 2 + loc.x, (frmSize.height - dlgSize.height) / 2 + loc.y);
        }

        setVisible(true);

    }

    private void initLayout() {

        notificationPanel.add(debugLabel, "alignx left");
        notificationPanel.add(libraryChannelsSelectAllLabel, "alignx left");
        notificationPanel.add(libraryChannelsDeselectAllLabel, "alignx left");

        notificationPanel.add(deployUndeployCheckBox, "newline, left");
        notificationPanel.add(attachmentBatchCheckBox, "newline, left");
        notificationPanel.add(sourceConnectorBatchCheckBox, "newline, left");
        notificationPanel.add(sourceFilterTransformerCheckBox, "newline, left");
        notificationPanel.add(destinationFilterTransformerCheckBox, "newline, left");
        notificationPanel.add(destinationScriptsCheckBox, "newline, left");
        notificationPanel.add(destinationRespCheckBox, "newline, left");

        add(notificationPanel, "grow, push, span");
        add(new JSeparator(), "grow, gaptop 4, span");

        add(okButton, " gapleft 110, gapright 10, width 60, spany 2");

        add(cancelButton, "gapright 110, width 60, spany 2");

    }

    private void initComponents() {
        setLayout(new MigLayout("insets 12", "[]", "[fill][]"));
        setTitle("Debug Channel");

        notificationPanel = new JPanel();
        notificationPanel.setLayout(new MigLayout("insets 0 0 0 0, fill", "[200!][]", "[25!]0[]"));

        debugLabel = new JLabel("Select channel script to debug");

        libraryChannelsSelectAllLabel = new JLabel("<html><u>Select All</u></html>");
        libraryChannelsSelectAllLabel.setForeground(Color.BLUE);
        libraryChannelsSelectAllLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        libraryChannelsSelectAllLabel.setToolTipText("Select all channel scripts.");
        libraryChannelsSelectAllLabel.addMouseListener(new MouseAdapter() {
            public void mouseReleased(MouseEvent evt) {
                deployUndeployCheckBox.setSelected(true);
                attachmentBatchCheckBox.setSelected(true);
                sourceConnectorBatchCheckBox.setSelected(true);
                sourceFilterTransformerCheckBox.setSelected(true);
                destinationFilterTransformerCheckBox.setSelected(true);
                destinationScriptsCheckBox.setSelected(true);
                destinationRespCheckBox.setSelected(true);

            }
        });

        libraryChannelsDeselectAllLabel = new JLabel("<html><u>Deselect All</u></html>");
        libraryChannelsDeselectAllLabel.setForeground(Color.BLUE);
        libraryChannelsDeselectAllLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        libraryChannelsDeselectAllLabel.setToolTipText("Deselect all channel scripts.");
        libraryChannelsDeselectAllLabel.addMouseListener(new MouseAdapter() {
            public void mouseReleased(MouseEvent evt) {
                deployUndeployCheckBox.setSelected(false);
                attachmentBatchCheckBox.setSelected(false);
                sourceConnectorBatchCheckBox.setSelected(false);
                sourceFilterTransformerCheckBox.setSelected(false);
                destinationFilterTransformerCheckBox.setSelected(false);
                destinationScriptsCheckBox.setSelected(false);
                destinationRespCheckBox.setSelected(false);

            }
        });

        deployUndeployCheckBox = new JCheckBox("Deploy/Undeploy/Preprocessor/Postprocessor scripts");
        attachmentBatchCheckBox = new JCheckBox("Attachment/Batch scripts");
        sourceConnectorBatchCheckBox = new JCheckBox("Source connector scripts");
        sourceFilterTransformerCheckBox = new JCheckBox("Source filter/transformer");
        destinationFilterTransformerCheckBox = new JCheckBox("Destination filter/transformer");
        destinationScriptsCheckBox = new JCheckBox("Destination connector scripts");
        destinationRespCheckBox = new JCheckBox("Destination response transformer");

        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });

        okButton = new JButton("OK");
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                if (deployUndeployCheckBox.isSelected()) {
                    debugOptions.setDeployUndeployPreAndPostProcessorScripts(true);
                }
                if (attachmentBatchCheckBox.isSelected()) {
                    debugOptions.setAttachmentBatchScripts(true);
                }
                if (sourceConnectorBatchCheckBox.isSelected()) {
                    debugOptions.setSourceConnectorScripts(true);
                }
                if (sourceFilterTransformerCheckBox.isSelected()) {
                    debugOptions.setSourceFilterTransformer(true);
                }
                if (destinationFilterTransformerCheckBox.isSelected()) {
                    debugOptions.setDestinationFilterTransformer(true);
                }
                if (destinationScriptsCheckBox.isSelected()) {
                    debugOptions.setDestinationConnectorScripts(true);
                }
                if (destinationRespCheckBox.isSelected()) {
                    debugOptions.setDestinationResponseTransformer(true);
                }
                isDebugChannel = true;

                dispose();
            }
        });

        pack();
    }

    private void close() {
        this.dispose();
    }

    @Override
    public void onCloseAction() {
        close();
    }

    public DebugOptions getDebugOptions() {
        return this.debugOptions;
    }
    
    public boolean getIsDebugChannel() {
        return isDebugChannel;
    }

    public static final String NEW_CHANNELS = "Debug Channel";
    JLabel debugLabel;
    JLabel libraryChannelsSelectAllLabel;
    JLabel libraryChannelsDeselectAllLabel;

    private JCheckBox deployUndeployCheckBox;
    private JCheckBox attachmentBatchCheckBox;
    private JCheckBox sourceConnectorBatchCheckBox;
    private JCheckBox sourceFilterTransformerCheckBox;
    private JCheckBox destinationFilterTransformerCheckBox;
    private JCheckBox destinationScriptsCheckBox;
    private JCheckBox destinationRespCheckBox;

    private JPanel notificationPanel;

    private JButton cancelButton;
    private JButton okButton;
}