package com.atharva.ollama.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.TextView;
import android.widget.Toast;

import com.facebook.shimmer.ShimmerFrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.atharva.ollama.R;
import com.atharva.ollama.model.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.Markwon;

/**
 * RecyclerView adapter for chat messages with markdown support.
 */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_ASSISTANT = 1;
    private static final int VIEW_TYPE_LOADING = 2;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Context context;
    private final Markwon markwon;
    private boolean isDarkMode = false;

    public ChatAdapter(Context context, Markwon markwon) {
        this.context = context;
        this.markwon = markwon;
        // setHasStableIds(true); // Disable stable IDs to avoid collisions with identical timestamps
    }

    public void setDarkMode(boolean darkMode) {
        this.isDarkMode = darkMode;
    }

    // Removed getItemId to fallback to default (NO_ID) since stable IDs are disabled
    // If stable IDs are needed, we must ensure unique IDs for every message including transient ones.


    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
        switch (message.getType()) {
            case USER:
                return VIEW_TYPE_USER;
            case ASSISTANT:
                return VIEW_TYPE_ASSISTANT;
            case LOADING:
            default:
                return VIEW_TYPE_LOADING;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_TYPE_USER:
                return new UserMessageViewHolder(
                        inflater.inflate(R.layout.item_message_user, parent, false));
            case VIEW_TYPE_ASSISTANT:
                return new AssistantMessageViewHolder(
                        inflater.inflate(R.layout.item_message_assistant, parent, false));
            case VIEW_TYPE_LOADING:
            default:
                return new LoadingViewHolder(
                        inflater.inflate(R.layout.item_message_loading, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);

        if (holder instanceof UserMessageViewHolder) {
            ((UserMessageViewHolder) holder).bind(message);
        } else if (holder instanceof AssistantMessageViewHolder) {
            ((AssistantMessageViewHolder) holder).bind(message);
        } else if (holder instanceof LoadingViewHolder) {
            LoadingViewHolder loadingHolder = (LoadingViewHolder) holder;
            loadingHolder.startAnimation();
            // restore status
            String status = message.getStatus();
            if (status != null) {
                loadingHolder.setStatus(status);
            } else {
                loadingHolder.setStatus(context.getString(R.string.status_thinking));
            }
        }
    }

    public interface OnMessageClickListener {
        void onSpeakClick(String text);
    }

    private OnMessageClickListener onMessageClickListener;

    public void setOnMessageClickListener(OnMessageClickListener listener) {
        this.onMessageClickListener = listener;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /**
     * Update message at specific position (for streaming).
     */
    public void updateMessageAt(int position, String content) {
        if (position >= 0 && position < messages.size()) {
            messages.get(position).setContent(content);
            notifyItemChanged(position, content); // Use payload to prevent full rebind
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            Object payload = payloads.get(0);
            if (holder instanceof AssistantMessageViewHolder && payload instanceof String) {
                ((AssistantMessageViewHolder) holder).updateContent((String) payload);
            } else if (holder instanceof LoadingViewHolder && payload instanceof String) {
                ((LoadingViewHolder) holder).setStatus((String) payload);
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    public void updateLastMessage(String content) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            ChatMessage lastMsg = messages.get(lastIndex);
            
            // Only notify if content actually changed to avoid over-rendering
            if (!content.equals(lastMsg.getContent())) {
                lastMsg.setContent(content);
                // Use payload to trigger partial update (bind only payload)
                notifyItemChanged(lastIndex, content); 
            }
        }
    }

    public void removeLastMessage() {
        if (!messages.isEmpty()) {
            messages.remove(messages.size() - 1);
            notifyItemRemoved(messages.size());
        }
    }

    public ChatMessage getLastMessage() {
        if (!messages.isEmpty()) {
            return messages.get(messages.size() - 1);
        }
        return null;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public void clearMessages() {
        int size = messages.size();
        messages.clear();
        notifyItemRangeRemoved(0, size);
    }

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
    }
    
    /**
     * Update the loading indicator status text.
     * @param status The status message to display (e.g., "Searching web...", "Thinking...", "Generating...")
     */
    public void updateLoadingStatus(String status) {
        if (!messages.isEmpty()) {
            int index = messages.size() - 1;
            ChatMessage last = messages.get(index);
            if (last.isLoading()) {
                last.setStatus(status);
                notifyItemChanged(index, status);
            }
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("message", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.message_copied, Toast.LENGTH_SHORT).show();
    }

    // ViewHolder for user messages
    class UserMessageViewHolder extends RecyclerView.ViewHolder {
        private final TextView txtMessage;
        private final TextView txtTime;

        UserMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            txtMessage = itemView.findViewById(R.id.txtMessage);
            txtTime = itemView.findViewById(R.id.txtTime);

            itemView.setOnLongClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    copyToClipboard(messages.get(position).getContent());
                }
                return true;
            });
        }

        void bind(ChatMessage message) {
            txtMessage.setText(message.getContent());
            txtTime.setText(timeFormat.format(new Date(message.getTimestamp())));
            
            // Handle Images
            android.widget.LinearLayout layoutImages = itemView.findViewById(R.id.layoutImages);
            if (layoutImages != null) {
                if (message.getImages() != null && !message.getImages().isEmpty()) {
                    layoutImages.setVisibility(View.VISIBLE);
                    layoutImages.removeAllViews();
                    
                    for (String path : message.getImages()) {
                        android.widget.ImageView img = new android.widget.ImageView(context);
                        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            400 // Fixed height for preview, or wrap_content
                        );
                        params.bottomMargin = 16;
                        img.setLayoutParams(params);
                        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                        img.setClipToOutline(true);
                        img.setBackgroundResource(android.R.drawable.dialog_holo_light_frame); // Minimal frame/placeholder
                        
                        try {
                            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path);
                            img.setImageBitmap(bitmap);
                            
                            // Open full size on click (optional, basic impl)
                            img.setOnClickListener(v -> {
                                 try {
                                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                                    intent.setDataAndType(androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        context.getPackageName() + ".provider",
                                        new java.io.File(path)
                                    ), "image/*");
                                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    context.startActivity(intent);
                                 } catch (Exception e) {
                                     // Fallback or ignore
                                 }
                            });
                            
                        } catch (Exception e) {
                            img.setBackgroundColor(android.graphics.Color.GRAY);
                        }
                        
                        layoutImages.addView(img);
                    }
                } else {
                    layoutImages.setVisibility(View.GONE);
                }
            }
        }
    }

    // ViewHolder for assistant messages with markdown
    class AssistantMessageViewHolder extends RecyclerView.ViewHolder {
        private final TextView txtMessage;
        private final TextView txtTime;
        private final android.widget.ImageButton btnSpeak;

        AssistantMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            txtMessage = itemView.findViewById(R.id.txtMessage);
            txtTime = itemView.findViewById(R.id.txtTime);
            btnSpeak = itemView.findViewById(R.id.btnSpeak);

            btnSpeak.setVisibility(View.VISIBLE);
            btnSpeak.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onMessageClickListener != null) {
                    onMessageClickListener.onSpeakClick(messages.get(position).getContent());
                }
            });

            itemView.setOnLongClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    copyToClipboard(messages.get(position).getContent());
                }
                return true;
            });
        }

        void bind(ChatMessage message) {
            // Parse for reasoning
            com.atharva.ollama.util.ReasoningHelper.ParseResult parseResult = com.atharva.ollama.util.ReasoningHelper.parse(message.getContent());
            
            updateContent(parseResult.finalAnswer);
            txtTime.setText(timeFormat.format(new Date(message.getTimestamp())));
            
            // Handle Thinking UI
            android.widget.LinearLayout layoutThinking = itemView.findViewById(R.id.layoutThinking);
            android.widget.LinearLayout headerThinking = itemView.findViewById(R.id.headerThinking);
            android.widget.TextView txtThinking = itemView.findViewById(R.id.txtThinking);
            android.widget.ImageView iconExpand = itemView.findViewById(R.id.iconExpand);
            
            if (parseResult.thinking != null && !parseResult.thinking.isEmpty()) {
                layoutThinking.setVisibility(View.VISIBLE);
                txtThinking.setText(parseResult.thinking);
                
                // Toggle logic
                headerThinking.setOnClickListener(v -> {
                    if (txtThinking.getVisibility() == View.VISIBLE) {
                        txtThinking.setVisibility(View.GONE);
                        iconExpand.setRotation(90); // Pointing right (collapsed) - assuming ic_menu rotated for chevron imitation or similar
                        // Better to use a real chevron, but keeping existing assets.
                    } else {
                        txtThinking.setVisibility(View.VISIBLE);
                        iconExpand.setRotation(270); // Pointing down (expanded)
                    }
                });
                
                // Default state: Collapsed unless generation is active? 
                // For now default collapsed to save space.
            } else {
                layoutThinking.setVisibility(View.GONE);
            }
            
            // Handle Sources
            com.google.android.material.chip.ChipGroup chipGroup = itemView.findViewById(R.id.chipGroupSources);
            if (message.getSources() != null && !message.getSources().isEmpty()) {
                chipGroup.setVisibility(View.VISIBLE);
                chipGroup.removeAllViews();
                
                for (ChatMessage.Source source : message.getSources()) {
                    com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(context);
                    chip.setText(source.title);
                    chip.setTooltipText(source.url); // Show URL on long press or hover
                    chip.setCheckable(false);
                    chip.setClickable(true);
                    
                    // Style the chip to look like an "elongated button"
                    chip.setChipIcon(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_globe)); 
                    chip.setIconStartPadding(8f);
                    
                    chip.setOnClickListener(v -> {
                        // Open URL
                        try {
                            android.content.Intent browserIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(source.url));
                            context.startActivity(browserIntent);
                        } catch (Exception e) {
                            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show();
                        }
                    });
                    
                    chipGroup.addView(chip);
                }
            } else {
                chipGroup.setVisibility(View.GONE);
            }
        }

        void updateContent(String content) {
            String initialText = (content == null || content.isEmpty()) ? "..." : content;
            
            // Re-apply markdown on every update (Markwon is fast enough for streaming chunks)
            // If performance is an issue, we can debounce this or use simple text for suffix.
            markwon.setMarkdown(txtMessage, initialText);
        }
    }

    // ViewHolder for loading indicator with modern shimmer animation
    class LoadingViewHolder extends RecyclerView.ViewHolder {
        private final ShimmerFrameLayout shimmerLayout;
        private final View dot1, dot2, dot3;
        private final TextView txtLoading;

        LoadingViewHolder(@NonNull View itemView) {
            super(itemView);
            shimmerLayout = itemView.findViewById(R.id.shimmerLayout);
            dot1 = itemView.findViewById(R.id.dot1);
            dot2 = itemView.findViewById(R.id.dot2);
            dot3 = itemView.findViewById(R.id.dot3);
            txtLoading = itemView.findViewById(R.id.txtLoading);
        }

        void startAnimation() {
            // Start shimmer
            if (shimmerLayout != null) {
                shimmerLayout.startShimmer();
            }
            
            // Animate dots with bounce effect
            animateDot(dot1, 0);
            animateDot(dot2, 150);
            animateDot(dot3, 300);
            
            // Status is handled by bind/payload now, don't reset it here blindly
        }
        
        void setStatus(String status) {
            if (txtLoading != null && status != null) {
                txtLoading.setText(status);
            }
        }
        
        private void animateDot(View dot, long delay) {
            if (dot == null) return;
            
            TranslateAnimation bounce = new TranslateAnimation(0, 0, 0, -10);
            bounce.setDuration(300);
            bounce.setRepeatMode(Animation.REVERSE);
            bounce.setRepeatCount(Animation.INFINITE);
            bounce.setStartOffset(delay);
            
            dot.startAnimation(bounce);
        }
        
        void stopAnimation() {
            if (shimmerLayout != null) {
                shimmerLayout.stopShimmer();
            }
            if (dot1 != null) dot1.clearAnimation();
            if (dot2 != null) dot2.clearAnimation();
            if (dot3 != null) dot3.clearAnimation();
        }
    }
}
