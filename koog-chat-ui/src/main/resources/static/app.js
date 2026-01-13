/**
 * Koog Chat UI - WebSocket Chat Application
 * Connects to a backend AI agent via WebSockets
 */

class KoogChat {
    constructor() {
        // DOM Elements
        this.elements = {
            backendUrl: document.getElementById('backendUrl'),
            sessionId: document.getElementById('sessionId'),
            messageFormat: document.getElementById('messageFormat'),
            connectBtn: document.getElementById('connectBtn'),
            disconnectBtn: document.getElementById('disconnectBtn'),
            clearChatBtn: document.getElementById('clearChatBtn'),
            newSessionBtn: document.getElementById('newSessionBtn'),
            messageForm: document.getElementById('messageForm'),
            messageInput: document.getElementById('messageInput'),
            sendBtn: document.getElementById('sendBtn'),
            messagesContainer: document.getElementById('messagesContainer'),
            connectionStatus: document.getElementById('connectionStatus'),
            typingIndicator: document.getElementById('typingIndicator'),
            agentStatus: document.getElementById('agentStatus'),
            messageTemplate: document.getElementById('messageTemplate'),
            progressTemplate: document.getElementById('progressTemplate')
        };

        // State
        this.ws = null;
        this.sessionId = null;
        this.isConnected = false;
        this.progressElement = null;

        // Initialize
        this.init();
    }

    init() {
        // Load saved settings
        this.loadSettings();

        // Fetch config from server
        this.fetchConfig();

        // Setup event listeners
        this.setupEventListeners();

        // Setup auto-resize textarea
        this.setupAutoResizeTextarea();
    }

    async fetchConfig() {
        try {
            const response = await fetch('/api/config');
            const config = await response.json();
            if (config.websocketUrl && !this.elements.backendUrl.value) {
                this.elements.backendUrl.value = config.websocketUrl;
            }
        } catch (e) {
            console.log('Could not fetch config, using defaults');
        }
    }

    loadSettings() {
        const savedUrl = localStorage.getItem('koog_backend_url');
        const savedSessionId = localStorage.getItem('koog_session_id');
        
        if (savedUrl) {
            this.elements.backendUrl.value = savedUrl;
        }
        if (savedSessionId) {
            this.elements.sessionId.value = savedSessionId;
        }
    }

    saveSettings() {
        localStorage.setItem('koog_backend_url', this.elements.backendUrl.value);
        if (this.sessionId) {
            localStorage.setItem('koog_session_id', this.sessionId);
        }
    }

    setupEventListeners() {
        // Connect button
        this.elements.connectBtn.addEventListener('click', () => this.connect());
        
        // Disconnect button
        this.elements.disconnectBtn.addEventListener('click', () => this.disconnect());
        
        // Clear chat button
        this.elements.clearChatBtn.addEventListener('click', () => this.clearChat());
        
        // New session button
        this.elements.newSessionBtn.addEventListener('click', () => this.newSession());
        
        // Full reset button
        const fullResetBtn = document.getElementById('fullResetBtn');
        if (fullResetBtn) {
            fullResetBtn.addEventListener('click', () => this.fullReset());
        }
        
        // Message form submit
        this.elements.messageForm.addEventListener('submit', (e) => {
            e.preventDefault();
            this.sendMessage();
        });
        
        // Enter to send (Shift+Enter for newline)
        this.elements.messageInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });
    }
    
    updateDebugInfo() {
        const sessionEl = document.getElementById('debugSessionId');
        const statusEl = document.getElementById('debugStatus');
        
        if (sessionEl) {
            sessionEl.textContent = this.sessionId || 'none';
        }
        if (statusEl) {
            statusEl.textContent = this.isConnected ? 'connected' : 'disconnected';
        }
    }

    setupAutoResizeTextarea() {
        const textarea = this.elements.messageInput;
        const maxHeight = 150;
        
        textarea.addEventListener('input', () => {
            textarea.style.height = 'auto';
            const newHeight = Math.min(textarea.scrollHeight, maxHeight);
            textarea.style.height = newHeight + 'px';
        });
    }

    connect() {
        const url = this.elements.backendUrl.value.trim();
        if (!url) {
            this.showToast('Please enter a backend WebSocket URL', 'error');
            return;
        }

        // IMPORTANT: Don't use old session IDs - let the backend create a new one
        // Old sessions may have expired or been cleared
        // Only use sessionId if explicitly provided by user
        let wsUrl = url;
        const userSessionId = this.elements.sessionId.value.trim();
        
        // Clear any old session state to start fresh
        this.sessionId = null;
        
        // Only append sessionId to URL if user explicitly provided one
        // Otherwise, let the backend create a new session
        if (userSessionId && userSessionId.length > 0) {
            console.log('Using user-provided session ID:', userSessionId);
            if (wsUrl.includes('/ws/')) {
                wsUrl = wsUrl.replace(/\/ws\/[^/]*$/, `/ws/${userSessionId}`);
            } else if (wsUrl.endsWith('/ws')) {
                wsUrl = `${wsUrl}/${userSessionId}`;
            } else {
                wsUrl = `${wsUrl}/${userSessionId}`;
            }
        } else {
            console.log('Connecting without session ID - backend will create new session');
            // Make sure URL ends with /ws without trailing session
            if (!wsUrl.endsWith('/ws')) {
                // Remove any trailing path after /ws
                wsUrl = wsUrl.replace(/\/ws\/.*$/, '/ws');
            }
        }

        console.log('Connecting to WebSocket URL:', wsUrl);
        this.updateConnectionStatus('connecting');
        
        try {
            this.ws = new WebSocket(wsUrl);
            
            this.ws.onopen = () => {
                console.log('WebSocket connection opened');
                this.isConnected = true;
                this.updateConnectionStatus('connected');
                this.updateUI(true);
                this.saveSettings();
                this.clearWelcomeMessage();
                this.showToast('Connected to backend', 'success');
            };
            
            this.ws.onmessage = (event) => {
                console.log('WebSocket message received:', event.data);
                this.handleMessage(event.data);
            };
            
            this.ws.onclose = (event) => {
                this.isConnected = false;
                this.updateConnectionStatus('disconnected');
                this.updateUI(false);
                
                if (event.wasClean) {
                    this.showToast('Disconnected from backend', 'warning');
                } else {
                    this.showToast('Connection lost unexpectedly', 'error');
                }
            };
            
            this.ws.onerror = (error) => {
                console.error('WebSocket error:', error);
                this.showToast('Connection error', 'error');
            };
        } catch (e) {
            console.error('Failed to connect:', e);
            this.showToast('Failed to connect: ' + e.message, 'error');
            this.updateConnectionStatus('disconnected');
        }
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }

    clearChat() {
        // Keep only the welcome message if not connected
        if (!this.isConnected) {
            this.elements.messagesContainer.innerHTML = '';
            this.showWelcomeMessage();
        } else {
            // Clear all messages
            this.elements.messagesContainer.innerHTML = '';
            this.addSystemMessage('Chat cleared');
        }
    }

    newSession() {
        console.log('Starting new session - clearing all state');
        this.disconnect();
        this.sessionId = null;
        this.elements.sessionId.value = '';
        localStorage.removeItem('koog_session_id');
        this.clearChat();
        this.showToast('Session cleared. Connect to start a new session.', 'success');
    }
    
    /**
     * Reset everything including localStorage
     */
    fullReset() {
        console.log('Full reset - clearing all localStorage and state');
        this.disconnect();
        this.sessionId = null;
        this.elements.sessionId.value = '';
        localStorage.clear();
        this.clearChat();
        this.showWelcomeMessage();
        this.showToast('Full reset complete. All data cleared.', 'success');
    }

    clearWelcomeMessage() {
        const welcome = this.elements.messagesContainer.querySelector('.welcome-message');
        if (welcome) {
            welcome.remove();
        }
    }

    showWelcomeMessage() {
        const welcome = document.createElement('div');
        welcome.className = 'welcome-message';
        welcome.innerHTML = `
            <div class="welcome-icon">💬</div>
            <h2>Welcome to SwarA</h2>
            <p>Connect to your backend WebSocket server and start chatting with your AI agent.</p>
            <div class="welcome-steps">
                <div class="step">
                    <span class="step-number">1</span>
                    <span>Configure the backend URL in the sidebar</span>
                </div>
                <div class="step">
                    <span class="step-number">2</span>
                    <span>Click "Connect" to establish connection</span>
                </div>
                <div class="step">
                    <span class="step-number">3</span>
                    <span>Start chatting with your AI agent!</span>
                </div>
            </div>
        `;
        this.elements.messagesContainer.appendChild(welcome);
    }

    updateConnectionStatus(status) {
        const statusEl = this.elements.connectionStatus;
        const indicator = statusEl.querySelector('.status-indicator');
        const text = statusEl.querySelector('span:last-child');
        
        indicator.className = 'status-indicator ' + status;
        
        switch (status) {
            case 'connected':
                text.textContent = 'Connected';
                this.elements.agentStatus.textContent = 'Online';
                break;
            case 'connecting':
                text.textContent = 'Connecting...';
                this.elements.agentStatus.textContent = 'Connecting...';
                break;
            case 'disconnected':
                text.textContent = 'Disconnected';
                this.elements.agentStatus.textContent = 'Offline';
                break;
        }
        
        this.updateDebugInfo();
    }

    updateUI(connected) {
        this.elements.connectBtn.style.display = connected ? 'none' : 'block';
        this.elements.disconnectBtn.style.display = connected ? 'block' : 'none';
        this.elements.messageInput.disabled = !connected;
        this.elements.sendBtn.disabled = !connected;
        
        if (connected) {
            this.elements.messageInput.focus();
        }
    }

    sendMessage() {
        const message = this.elements.messageInput.value.trim();
        if (!message || !this.isConnected || !this.ws) {
            return;
        }

        // Add user message to UI immediately
        this.addMessage(message, 'user');
        
        // Clear input and reset height
        this.elements.messageInput.value = '';
        this.elements.messageInput.style.height = 'auto';
        
        // Get the message format from the selector
        const format = this.elements.messageFormat?.value || 'text';
        
        let payload;
        if (format === 'json') {
            // Full JSON with sessionId
            payload = JSON.stringify({
                sessionId: this.sessionId,
                message: message
            });
            console.log('Sent full JSON message:', payload);
        } else if (format === 'json_simple') {
            // Simple JSON with just message (like Postman might send)
            payload = JSON.stringify({
                message: message
            });
            console.log('Sent simple JSON message:', payload);
        } else {
            // Send plain text
            payload = message;
            console.log('Sent plain text message:', message);
        }
        
        console.log('Current sessionId:', this.sessionId);
        console.log('Payload type:', typeof payload);
        console.log('Payload length:', payload.length);
        console.log('Payload bytes:', Array.from(payload).map(c => c.charCodeAt(0)));
        
        this.ws.send(payload);
        
        // Show typing indicator
        this.showTypingIndicator(true);
    }

    handleMessage(data) {
        try {
            const event = JSON.parse(data);
            console.log('Received event:', event.type, event);
            
            // Always update session ID from server response
            if (event.sessionId) {
                if (this.sessionId && this.sessionId !== event.sessionId) {
                    console.warn('Session ID changed from', this.sessionId, 'to', event.sessionId);
                }
                this.sessionId = event.sessionId;
                this.elements.sessionId.value = this.sessionId;
                this.saveSettings();
                this.updateDebugInfo();
            }
            
            switch (event.type) {
                case 'connected':
                    console.log('Connected to session:', event.sessionId);
                    this.addSystemMessage(`Connected to session: ${event.sessionId?.substring(0, 8)}...`);
                    if (event.sessionId) {
                        this.sessionId = event.sessionId;
                        this.elements.sessionId.value = this.sessionId;
                        this.saveSettings();
                        this.updateDebugInfo();
                    }
                    break;
                    
                case 'user_message':
                    // We already show user messages locally
                    break;
                    
                case 'assistant_message':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    if (event.message && event.message.content) {
                        this.addMessage(event.message.content, 'agent', event.message.type);
                    } else if (event.content) {
                        this.addMessage(event.content, 'agent');
                    }
                    break;
                    
                case 'progress':
                    this.showTypingIndicator(false);
                    this.updateProgressIndicator(event.content);
                    break;
                    
                case 'plan_result':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    if (event.message && event.message.content) {
                        this.addMessage(event.message.content, 'agent', 'plan');
                    }
                    break;
                    
                case 'interactive_checkpoint':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    if (event.message && event.message.content) {
                        this.addMessage(event.message.content, 'agent', 'interactive');
                    }
                    break;
                
                // ============ Suggestion Events ============
                case 'suggestion':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addSpecialCard(event, 'suggestion', '💡', 'Suggestion');
                    break;
                
                // ============ Selection Events ============
                case 'branch_selection':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addSpecialCard(event, 'branch_selection', '🏢', 'Select Branch');
                    break;
                    
                case 'timeslot_confirmation':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addSpecialCard(event, 'timeslot', '🕐', 'Timeslot');
                    break;
                    
                case 'booking_confirmation':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addSpecialCard(event, 'booking', '📋', 'Confirm Booking');
                    break;
                
                // ============ Result Events ============
                case 'appointment_result':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addResultCard(event, 'appointment', '✅', 'Appointment Confirmed');
                    break;
                    
                case 'weather_forecast':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addSpecialCard(event, 'weather', '🌤️', 'Weather Forecast');
                    break;
                    
                case 'parking_info':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    this.addSpecialCard(event, 'parking', '🅿️', 'Parking Information');
                    break;
                    
                case 'error':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    const errorMsg = event.message?.content || event.content || 'An error occurred';
                    this.addMessage(errorMsg, 'agent', 'error');
                    this.showToast('Error: ' + errorMsg, 'error');
                    break;
                    
                case 'done':
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    break;
                    
                default:
                    // Handle any other event types generically
                    console.log('Event type:', event.type, event);
                    this.showTypingIndicator(false);
                    this.removeProgressIndicator();
                    
                    // Try to display content from the event
                    const content = event.message?.content || event.content;
                    if (content) {
                        this.addMessage(content, 'agent', event.type);
                    }
                    break;
            }
        } catch (e) {
            console.error('Error parsing message:', e);
            // Treat as plain text message
            this.addMessage(data, 'agent');
        }
    }
    
    /**
     * Add a special card for interactive events like suggestions, selections, etc.
     */
    addSpecialCard(event, cardType, icon, title) {
        const content = event.message?.content || event.content || '';
        
        const messageEl = document.createElement('div');
        messageEl.className = 'message message-agent';
        messageEl.innerHTML = `
            <div class="message-bubble special-card special-card-${cardType}">
                <div class="special-card-header">
                    <span class="special-card-icon">${icon}</span>
                    <span class="special-card-title">${title}</span>
                </div>
                <div class="special-card-content">
                    ${this.parseMarkdown(content)}
                </div>
                <div class="message-time">${this.formatTime(new Date())}</div>
            </div>
        `;
        
        this.elements.messagesContainer.appendChild(messageEl);
        this.scrollToBottom();
    }
    
    /**
     * Add a result card for completed actions like appointments, bookings, etc.
     */
    addResultCard(event, cardType, icon, title) {
        const content = event.message?.content || event.content || '';
        const result = event.appointmentResult || event.planResult || null;
        
        let resultDetails = '';
        if (result) {
            resultDetails = `
                <div class="result-details">
                    ${Object.entries(result).map(([key, value]) => {
                        if (value && typeof value !== 'object') {
                            const formattedKey = key.replace(/([A-Z])/g, ' $1').replace(/^./, str => str.toUpperCase());
                            return `<div class="result-item"><span class="result-label">${formattedKey}:</span> <span class="result-value">${value}</span></div>`;
                        }
                        return '';
                    }).join('')}
                </div>
            `;
        }
        
        const messageEl = document.createElement('div');
        messageEl.className = 'message message-agent';
        messageEl.innerHTML = `
            <div class="message-bubble result-card result-card-${cardType}">
                <div class="result-card-header">
                    <span class="result-card-icon">${icon}</span>
                    <span class="result-card-title">${title}</span>
                </div>
                <div class="result-card-content">
                    ${this.parseMarkdown(content)}
                </div>
                ${resultDetails}
                <div class="message-time">${this.formatTime(new Date())}</div>
            </div>
        `;
        
        this.elements.messagesContainer.appendChild(messageEl);
        this.scrollToBottom();
    }

    addMessage(content, role, type = 'text') {
        const template = this.elements.messageTemplate.content.cloneNode(true);
        const messageEl = template.querySelector('.message');
        const bubbleEl = template.querySelector('.message-bubble');
        const contentEl = template.querySelector('.message-content');
        const timeEl = template.querySelector('.message-time');
        
        // Set role class
        messageEl.classList.add(`message-${role}`);
        
        // Parse markdown-like content for agent messages
        if (role === 'agent') {
            contentEl.innerHTML = this.parseMarkdown(content);
        } else {
            contentEl.textContent = content;
        }
        
        // Normalize type to lowercase for comparison
        const normalizedType = (type || 'text').toString().toLowerCase();
        
        // Add type class for special styling
        switch (normalizedType) {
            case 'error':
                bubbleEl.style.borderColor = 'var(--error)';
                bubbleEl.classList.add('error');
                break;
            case 'plan':
            case 'plan_result':
                bubbleEl.classList.add('plan-bubble');
                break;
            case 'thinking':
                bubbleEl.classList.add('thinking');
                // Add thinking icon prefix
                const thinkingPrefix = '<span class="thinking-icon">💭</span> ';
                contentEl.innerHTML = thinkingPrefix + contentEl.innerHTML;
                break;
            case 'tool_use':
                bubbleEl.classList.add('tool-use');
                const toolUsePrefix = '<span class="tool-icon">🔧</span> <strong>Using tool:</strong><br>';
                contentEl.innerHTML = toolUsePrefix + contentEl.innerHTML;
                break;
            case 'tool_result':
                bubbleEl.classList.add('tool-result');
                const toolResultPrefix = '<span class="tool-icon">📋</span> <strong>Tool result:</strong><br>';
                contentEl.innerHTML = toolResultPrefix + contentEl.innerHTML;
                break;
            case 'interactive':
                bubbleEl.classList.add('interactive');
                break;
            case 'appointment_result':
                bubbleEl.classList.add('result-card');
                break;
            default:
                // No special styling for unknown types
                break;
        }
        
        // Set timestamp
        timeEl.textContent = this.formatTime(new Date());
        
        // Add to container
        this.elements.messagesContainer.appendChild(messageEl);
        
        // Scroll to bottom
        this.scrollToBottom();
    }

    addSystemMessage(content) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message message-system';
        messageEl.innerHTML = `
            <div class="message-bubble">
                <div class="message-content">${content}</div>
            </div>
        `;
        this.elements.messagesContainer.appendChild(messageEl);
        this.scrollToBottom();
    }

    updateProgressIndicator(content) {
        if (!this.progressElement) {
            const template = this.elements.progressTemplate.content.cloneNode(true);
            this.progressElement = template.querySelector('.message');
            this.elements.messagesContainer.appendChild(this.progressElement);
        }
        
        const textEl = this.progressElement.querySelector('.progress-text');
        textEl.innerHTML = this.parseMarkdown(content);
        this.scrollToBottom();
    }

    removeProgressIndicator() {
        if (this.progressElement) {
            this.progressElement.remove();
            this.progressElement = null;
        }
    }

    showTypingIndicator(show) {
        this.elements.typingIndicator.style.display = show ? 'inline-flex' : 'none';
        this.elements.agentStatus.style.display = show ? 'none' : 'inline';
    }

    parseMarkdown(text) {
        if (!text) return '';
        
        // Escape HTML first
        let html = text
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
        
        // Headers
        html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
        html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
        html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');
        
        // Bold
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        
        // Italic
        html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
        
        // Code blocks
        html = html.replace(/```(\w*)\n?([\s\S]*?)```/g, '<pre><code>$2</code></pre>');
        
        // Inline code
        html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
        
        // Unordered lists
        html = html.replace(/^[•\-\*] (.+)$/gm, '<li>$1</li>');
        html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>');
        
        // Ordered lists
        html = html.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
        
        // Horizontal rules
        html = html.replace(/^---$/gm, '<hr>');
        
        // Blockquotes
        html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>');
        
        // Line breaks (but not in code blocks or lists)
        html = html.replace(/\n\n/g, '</p><p>');
        html = html.replace(/\n/g, '<br>');
        
        // Wrap in paragraph if not already wrapped
        if (!html.startsWith('<')) {
            html = '<p>' + html + '</p>';
        }
        
        return html;
    }

    formatTime(date) {
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }

    scrollToBottom() {
        const container = this.elements.messagesContainer;
        container.scrollTop = container.scrollHeight;
    }

    showToast(message, type = 'info') {
        // Create toast container if it doesn't exist
        let container = document.querySelector('.toast-container');
        if (!container) {
            container = document.createElement('div');
            container.className = 'toast-container';
            document.body.appendChild(container);
        }
        
        // Create toast
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        
        container.appendChild(toast);
        
        // Remove after 4 seconds
        setTimeout(() => {
            toast.style.animation = 'toastIn 0.3s ease-out reverse';
            setTimeout(() => toast.remove(), 300);
        }, 4000);
    }
}

// Initialize the chat application
document.addEventListener('DOMContentLoaded', () => {
    window.koogChat = new KoogChat();
});

