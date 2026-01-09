package com.atharva.ollama.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.atharva.ollama.R;
import com.atharva.ollama.database.Conversation;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for conversation list in drawer.
 */
public class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ViewHolder> {

    private final List<Conversation> conversations = new ArrayList<>();
    private final ConversationClickListener listener;
    private long selectedConversationId = -1;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());

    public interface ConversationClickListener {
        void onConversationClick(Conversation conversation);
        void onConversationDelete(Conversation conversation);
    }

    public ConversationAdapter(ConversationClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Conversation conversation = conversations.get(position);
        holder.bind(conversation, conversation.id == selectedConversationId);
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    public void setConversations(List<Conversation> newConversations) {
        conversations.clear();
        conversations.addAll(newConversations);
        notifyDataSetChanged();
    }

    public void setSelectedConversationId(long id) {
        long oldId = selectedConversationId;
        selectedConversationId = id;
        
        // Update old and new selected items
        for (int i = 0; i < conversations.size(); i++) {
            if (conversations.get(i).id == oldId || conversations.get(i).id == id) {
                notifyItemChanged(i);
            }
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView txtTitle;
        private final TextView txtDate;
        private final ImageButton btnDelete;
        private final View container;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtTitle = itemView.findViewById(R.id.txtConversationTitle);
            txtDate = itemView.findViewById(R.id.txtConversationDate);
            btnDelete = itemView.findViewById(R.id.btnDeleteConversation);
            container = itemView.findViewById(R.id.conversationContainer);
        }

        void bind(Conversation conversation, boolean isSelected) {
            txtTitle.setText(conversation.title);
            txtDate.setText(dateFormat.format(new Date(conversation.updatedAt)));

            // Highlight selected conversation
            container.setAlpha(isSelected ? 1.0f : 0.7f);
            container.setSelected(isSelected);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onConversationClick(conversation);
                }
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onConversationDelete(conversation);
                }
            });
        }
    }
}
