#--
# Copyright (c) 2010-2012 Engine Yard, Inc.
# Copyright (c) 2007-2009 Sun Microsystems, Inc.
# This source code is available under the MIT license.
# See the file LICENSE.txt for details.
#++

require 'rack/handler/servlet'

module Rack
  module Handler
    class Servlet
      # Provides a (default) Servlet to Rack environment conversion.
      # Rack builtin requirements, CGI variables and HTTP headers are to be 
      # filled from the Servlet API.
      # Parameter parsing is left to be done by Rack::Request itself (e.g. by 
      # consuming the request body in case of a POST), thus this expects the 
      # ServletRequest input stream to be not read (e.g. for POSTs).
      class DefaultEnv
        
        BUILTINS = %w(rack.version rack.input rack.errors rack.url_scheme 
          rack.multithread rack.multiprocess rack.run_once
          java.servlet_request java.servlet_response java.servlet_context
          jruby.rack.version jruby.rack.jruby.version jruby.rack.rack.release).
          map!(&:freeze)

        VARIABLES = %w(CONTENT_TYPE CONTENT_LENGTH PATH_INFO QUERY_STRING 
          REMOTE_ADDR REMOTE_HOST REMOTE_USER REQUEST_METHOD REQUEST_URI
          SCRIPT_NAME SERVER_NAME SERVER_PORT SERVER_SOFTWARE).
          map!(&:freeze)
        
        # Factory method for creating the Hash.
        # Besides initializing a new env instance this method by default 
        # eagerly populates (and returns) the env Hash.
        # 
        # Subclasses might decide to change this behavior by overriding
        # this method (NOTE: that #initialize returns a lazy instance).
        # 
        # However keep in mind that some Rack middleware or extension might
        # dislike a lazy env since it does not reflect env.keys "correctly".
        def self.create(servlet_env)
          self.new(servlet_env).populate
        end
        
        # Initialize this (Rack) environment from the servlet environment.
        # 
        # The returned instance is lazy as much as possible (the env hash 
        # returned from #to_hash will be filled on demand), one can use 
        # #populate to fill in the env keys eagerly.
        def initialize(servlet_env)
          @servlet_env = servlet_env
          @env = Hash.new { |env, key| load_env_key(env, key) }
          # always pre-load since they might override variables
          load_attributes
        end

        def populate
          load_builtins
          load_variables
          load_headers
          @env
        end

        def to_hash
          @env
        end
        
        protected

        def load_attributes
          @servlet_env.getAttributeNames.each do |k|
            v = @servlet_env.getAttribute(k)
            case k
            when 'SERVER_PORT', 'CONTENT_LENGTH'
              @env[k] = v.to_s if v.to_i >= 0
            when 'CONTENT_TYPE'
              @env[k] = v if v
            else
              @env[k] = v ? v : ''
            end
          end
        end

        def load_builtins
          for b in BUILTINS
            load_builtin(@env, b) unless @env.has_key?(b)
          end
        end

        def load_variables
          for v in VARIABLES
            load_variable(@env, v) unless @env.has_key?(v)
          end
        end
        
        @@content_header_names = /^Content-(Type|Length)$/i
        
        def load_headers
          # NOTE: getHeaderNames and getHeaders might return null !
          # if the container does not allow access to header information
          return unless @servlet_env.getHeaderNames
          @servlet_env.getHeaderNames.each do |name|
            next if name =~ @@content_header_names
            key = "HTTP_#{name.upcase.gsub(/-/, '_')}".freeze
            @env[key] = @servlet_env.getHeader(name) unless @env.has_key?(key)
          end
        end

        def load_env_key(env, key)
          # rack-cache likes to freeze: `Request.new(@env.dup.freeze)`
          return if env.frozen?
          
          if key =~ /^(rack|java|jruby)/
            load_builtin(env, key)
          elsif key[0, 5] == 'HTTP_'
            load_header(env, key)
          else
            load_variable(env, key)
          end
        end

        def load_header(env, key)
          name = key.sub('HTTP_', '').
            split('_').each { |w| w.downcase!; w.capitalize! }.join('-')
          return if name =~ @@content_header_names
          if header = @servlet_env.getHeader(name)
            env[key] = header # null if it does not have a header of that name
          end
        end

        def load_builtin(env, key)
          case key
          when 'rack.version'         then env[key] = ::Rack::VERSION
          when 'rack.multithread'     then env[key] = true
          when 'rack.multiprocess'    then env[key] = false
          when 'rack.run_once'        then env[key] = false
          when 'rack.input'           then env[key] = @servlet_env.to_io
          when 'rack.errors'          then env[key] = JRuby::Rack::ServletLog.new(rack_context)
          when 'rack.url_scheme'
            env[key] = scheme = @servlet_env.getScheme
            env['HTTPS'] = 'on' if scheme == 'https'
            scheme
          when 'java.servlet_request'
            env[key] = @servlet_env.respond_to?(:request) ? @servlet_env.request : @servlet_env
          when 'java.servlet_response'
            env[key] = @servlet_env.respond_to?(:response) ? @servlet_env.response : @servlet_env
          when 'java.servlet_context' then env[key] = servlet_context
          when 'jruby.rack.context'   then env[key] = rack_context
          when 'jruby.rack.version'   then env[key] = JRuby::Rack::VERSION
          when 'jruby.rack.jruby.version' then env[key] = JRUBY_VERSION
          when 'jruby.rack.rack.release'  then env[key] = ::Rack.release
          else
            nil
          end
        end

        def load_variable(env, key)
          case key
          when 'CONTENT_TYPE'
            content_type = @servlet_env.getContentType
            env[key] = content_type if content_type
          when 'CONTENT_LENGTH'
            content_length = @servlet_env.getContentLength
            env[key] = content_length.to_s if content_length >= 0
          when 'PATH_INFO'       then env[key] = @servlet_env.getPathInfo
          when 'QUERY_STRING'    then env[key] = @servlet_env.getQueryString || ''
          when 'REMOTE_ADDR'     then env[key] = @servlet_env.getRemoteAddr || ''
          when 'REMOTE_HOST'     then env[key] = @servlet_env.getRemoteHost || ''
          when 'REMOTE_USER'     then env[key] = @servlet_env.getRemoteUser || ''
          when 'REQUEST_METHOD'  then env[key] = @servlet_env.getMethod || 'GET'
          when 'REQUEST_URI'     then env[key] = @servlet_env.getRequestURI
          when 'SCRIPT_NAME'     then env[key] = @servlet_env.getScriptName
          when 'SERVER_NAME'     then env[key] = @servlet_env.getServerName || ''
          when 'SERVER_PORT'     then env[key] = @servlet_env.getServerPort.to_s
          when 'SERVER_SOFTWARE' then env[key] = rack_context.getServerInfo
          else
            nil
          end
        end

        private

        def rack_context
          @rack_context ||=            
            if @servlet_env.respond_to?(:context)
              @servlet_env.context # RackEnvironment#getContext()
            else
              JRuby::Rack.context || raise("missing rack context")
            end
        end

        def servlet_context
          if @servlet_env.respond_to?(:servlet_context) # @since Servlet 3.0
            @servlet_env.servlet_context # ServletRequest#getServletContext()
          else
            if @servlet_env.context.is_a?(javax.servlet.ServletContext)
              @servlet_env.context
            else
              JRuby::Rack.context || @env['java.servlet_request'].servlet_context
            end
          end
        end
        
      end
    end
  end
end
