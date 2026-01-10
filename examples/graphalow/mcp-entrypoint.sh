#!/bin/sh
# Generate /.env from environment variables
# Add new keys here in the heredoc below

cat > /.mcp-secrets.env << EOF
google-maps-comprehensive.api_key=${GOOGLE_MAPS_API_KEY}
tavily.api_key=${TAVILY_API_TOKEN}
EOF

# Execute the gateway with all arguments passed to this script
exec /docker-mcp gateway run "$@"
